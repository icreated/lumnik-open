package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-pool CRUD on core.search_projection_state (V57, design doc §3/§6) — the SearchProjectionMarkStore
 * sibling for this table. The worker owns every write to this table; Task 9's SearchOperationsResource
 * is the one request path allowed to read it (admin pool, so every read method takes an explicit
 * tenantId and filters on it — RLS does not apply here).
 */
@ApplicationScoped
public class SearchProjectionStateStore {

    /** {@link #find}'s and {@link #findBuilding}'s shared column list, in the order {@link #mapRow}
     *  reads them back by ordinal. */
    private static final String COLUMNS =
        "tenant_id, search_index, provider_name, live_generation, position, status, retry_count, last_error, "
      + "active_run_id, active_run_phase, active_run_started_at, active_generation, active_provider_name, "
      + "cancel_requested_run_id, last_rebuild_run_id, last_rebuild_outcome, last_rebuild_finished_at, "
      + "last_document_count, last_document_count_at";

    private static SearchProjectionState mapRow(ResultSet rs) throws SQLException {
        return new SearchProjectionState(rs.getLong(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getLong(5), rs.getString(6), rs.getInt(7), rs.getString(8),
            rs.getObject(9, UUID.class), rs.getString(10), instant(rs.getTimestamp(11)),
            rs.getString(12), rs.getString(13),
            rs.getObject(14, UUID.class),
            rs.getObject(15, UUID.class), rs.getString(16), instant(rs.getTimestamp(17)),
            (Long) rs.getObject(18), instant(rs.getTimestamp(19)));
    }

    /** {@code ExportRunLedger}'s own null-safe timestamp-to-Instant helper, copied rather than shared —
     *  this store has no existing dependency on the export package. */
    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Inject @DataSource("admin") AgroalDataSource ds;

    public Optional<SearchProjectionState> find(long tenantId, String searchIndex) {
        return Jdbc.queryOne(ds, "SearchProjectionStateStore.find",
                // String.format, not "..." + COLUMNS + "...": AdminDatasourceTenantGuardTest scans
                // for verb+table co-occurring in ONE literal run joined only by `+` — splicing COLUMNS
                // in with `+` breaks that run in two, making the WHERE clause invisible to it.
                String.format("SELECT %s FROM core.search_projection_state WHERE tenant_id = ? AND search_index = ?", COLUMNS),
                ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); },
                SearchProjectionStateStore::mapRow);
    }

    /**
     * {@link #find}'s own row for every (tenant, index) pair that has a state row at all, in one
     * query — {@link SearchProjectionWorker#discoveryTick}'s own per-tick dispatch decision, which
     * used to call {@link #find} once per pair (the same N+1 shape
     * {@link io.lumnik.hub.search.mark.SearchProjectionMarkStore#validIndexNamesByEntityAndTenantAdmin}
     * was already fixed for one level up). A pair absent here means "no state row yet", exactly what
     * an empty {@link #find} result already meant.
     */
    public Map<Long, Map<String, SearchProjectionState>> findAllAdmin() {
        List<SearchProjectionState> rows = Jdbc.query(ds, "SearchProjectionStateStore.findAllAdmin",
            String.format("SELECT %s FROM core.search_projection_state", COLUMNS),
            ps -> {}, SearchProjectionStateStore::mapRow);

        Map<Long, Map<String, SearchProjectionState>> byTenant = new LinkedHashMap<>();
        for (SearchProjectionState state : rows) {
            byTenant.computeIfAbsent(state.tenantId(), k -> new LinkedHashMap<>()).put(state.searchIndex(), state);
        }
        return byTenant;
    }

    /** {@link #findAllAdmin()}'s own tenant-narrowed sibling — #703's own list door, admin pool, so
     *  filtered explicitly rather than relying on RLS (Review Focus). */
    public Map<String, SearchProjectionState> findAllForTenant(long tenantId) {
        List<SearchProjectionState> rows = Jdbc.query(ds, "SearchProjectionStateStore.findAllForTenant",
            String.format("SELECT %s FROM core.search_projection_state WHERE tenant_id = ?", COLUMNS),
            ps -> ps.setLong(1, tenantId),
            SearchProjectionStateStore::mapRow);
        Map<String, SearchProjectionState> byIndex = new LinkedHashMap<>();
        for (SearchProjectionState row : rows) byIndex.put(row.searchIndex(), row);
        return byIndex;
    }

    /**
     * #703's own document-count cache (design doc §4): written opportunistically whenever a live
     * ProjectionSink.liveDocumentCount call succeeds. A row absent (never built) makes this a silent
     * zero-row UPDATE — nothing to cache against yet.
     *
     * <p>{@code expectedLiveGeneration} guards against a stale write racing a cutover (code review
     * finding): a GET reading a live count for generation G1 can race a concurrent {@code markReady}
     * that cuts the row over to G2 (clearing the document-count cache as part of that same atomic
     * write) — without this guard, the G1 count could land back on the row AFTER the clear, served as
     * if it belonged to G2. Bound to whatever {@code live_generation} the caller observed BEFORE
     * making its own live count call; {@code IS NOT DISTINCT FROM} treats both-null (no generation
     * yet, either observed or currently stored) as a match, exactly like a plain {@code =} would for
     * two non-null values. A mismatch (or the row no longer existing) is a silent zero-row UPDATE,
     * same posture as an absent row — never an exception.
     *
     * <p>Returns whether the row still matched (Copilot finding): {@code false} means the exact same
     * cutover this guard exists for just happened, so {@code count} itself belongs to whatever
     * generation is now live, not the one the caller's response is about to report — the caller must
     * not serve it as this generation's fresh count.
     *
     * <p>Also refuses while {@code active_run_phase = 'publishing'} (code review finding): {@code
     * sink.publish(gen)} runs BEFORE {@code commitMarkCleanupAndMarkReady} moves {@code
     * live_generation} forward, so a call that read {@code expectedLiveGeneration} while the pair
     * was still {@code building} (or with no active run at all) can have its live count call and
     * this write straddle the exact instant the pair enters {@code publishing} — {@code
     * live_generation} alone would still match at write time, but the count in hand may already
     * belong to the NEW generation the provider just started serving. Checked in the SAME statement,
     * not a separate read first: only that closes the gap rather than moving it.
     */
    public boolean updateDocumentCount(long tenantId, String searchIndex, long count, String expectedLiveGeneration) {
        return Jdbc.update(ds, "SearchProjectionStateStore.updateDocumentCount",
            "UPDATE core.search_projection_state SET last_document_count = ?, last_document_count_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND live_generation IS NOT DISTINCT FROM ? "
          + "AND active_run_phase IS DISTINCT FROM 'publishing'",
            ps -> {
                ps.setLong(1, count);
                ps.setLong(2, tenantId);
                ps.setString(3, searchIndex);
                ps.setString(4, expectedLiveGeneration);
            }) == 1;
    }

    /** §3.1 step 3's own transition: no state row yet -> insert building, claiming (runId, startedAt) in
     *  the same statement — never in violation to begin with. */
    public void insertBuilding(long tenantId, String searchIndex, UUID runId, Instant startedAt) {
        Jdbc.update(ds, "SearchProjectionStateStore.insertBuilding",
            "INSERT INTO core.search_projection_state "
          + "(tenant_id, search_index, status, active_run_id, active_run_phase, active_run_started_at) "
          + "VALUES (?, ?, 'building', ?, 'building', ?) "
          + "ON CONFLICT (tenant_id, search_index) DO NOTHING",
            ps -> {
                ps.setLong(1, tenantId);
                ps.setString(2, searchIndex);
                ps.setObject(3, runId);
                ps.setObject(4, Timestamp.from(startedAt));
            });
    }

    /** §3.1 step 3's own transition: existing ready/degraded row -> building, last_error cleared, and
     *  the SAME run-claim as insertBuilding — a scheduler-driven rebuild is exactly as observable/
     *  cancellable as an on-demand one (design doc §3.1). live_generation/provider_name untouched. */
    public void transitionToBuilding(long tenantId, String searchIndex, UUID runId, Instant startedAt) {
        Jdbc.update(ds, "SearchProjectionStateStore.transitionToBuilding",
            "UPDATE core.search_projection_state SET status = 'building', last_error = NULL, "
          + "active_run_id = ?, active_run_phase = 'building', active_run_started_at = ?, "
          + "cancel_requested_run_id = NULL, active_generation = NULL, active_provider_name = NULL, "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setObject(1, runId);
                ps.setObject(2, Timestamp.from(startedAt));
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
            });
    }

    private static final String MARK_READY_SQL =
        "UPDATE core.search_projection_state SET provider_name = ?, live_generation = ?, "
      + "position = GREATEST(position, ?), status = 'ready', retry_count = 0, last_error = NULL, "
      + "updated_at = now() WHERE tenant_id = ? AND search_index = ?";

    /** Opens its own admin connection — see the {@link Connection} overload for the case that must
     *  commit atomically with another write on the caller's own transaction ({@code
     *  SearchProjectionWorker.runBuild}'s mark-cleanup + markReady tail, I3 fix, #671 final review).
     *
     *  <p>{@code position} is advanced with the same {@code GREATEST} monotonicity as {@link
     *  #advancePosition} (#712 follow-up): a rebuild's own watermark is the mark table's current max
     *  id at read time, which can sit BELOW a position catch-up already advanced further — a late ALL
     *  mark with a lower id, committed after a higher-id KEY mark was already caught up, triggers
     *  exactly this rebuild with a lower watermark. Regressing position there would violate the
     *  diagnostic-only, never-decreasing invariant this counter now claims; the rebuild's own
     *  correctness never depended on this value, since {@code runBuild} deletes marks by the
     *  watermark it just read, not by comparing against the stored position. */
    public void markReady(long tenantId, String searchIndex, String providerName, String generation, long position) {
        Jdbc.update(ds, "SearchProjectionStateStore.markReady", MARK_READY_SQL,
            ps -> {
                ps.setString(1, providerName);
                ps.setString(2, generation);
                ps.setLong(3, position);
                ps.setLong(4, tenantId);
                ps.setString(5, searchIndex);
            });
    }

    private static final String MARK_READY_ENDING_RUN_SQL =
        "UPDATE core.search_projection_state SET provider_name = ?, live_generation = ?, "
      + "position = GREATEST(position, ?), status = 'ready', retry_count = 0, last_error = NULL, "
      + "last_document_count = NULL, last_document_count_at = NULL, "
      + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
      + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
      + "last_rebuild_run_id = ?, last_rebuild_outcome = 'ready', last_rebuild_finished_at = now(), "
      + "updated_at = now() WHERE tenant_id = ? AND search_index = ?";

    /** {@link #markReady(long, String, String, String, long)}'s own {@link Connection} overload —
     *  production's only caller ({@code SearchProjectionWorker.commitMarkCleanupAndMarkReady}) — but
     *  with a genuinely different SQL: this one ends the claimed run and stamps last_rebuild, plus
     *  clears the document-count cache unconditionally (design doc §2: a successful rebuild always
     *  installs a DISTINCT generation, so any cached count belongs to a generation that is now gone).
     *  No shared binder with the plain 5-arg {@code markReady} any more — the two SQL strings no
     *  longer share column order, so sharing one was only ever a coincidence. */
    public void markReady(Connection c, long tenantId, String searchIndex, String providerName, String generation,
                           long position, UUID runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(MARK_READY_ENDING_RUN_SQL)) {
            ps.setString(1, providerName);
            ps.setString(2, generation);
            ps.setLong(3, position);
            ps.setObject(4, runId);
            ps.setLong(5, tenantId);
            ps.setString(6, searchIndex);
            ps.executeUpdate();
        }
    }

    /** §3.1 step 4 / §2: written the instant sink.beginGeneration returns, so the reaper (Task 7) can
     *  call sink.abandon after a crash even though the in-process local variable holding this token is
     *  gone. */
    public void recordActiveGeneration(long tenantId, String searchIndex, String generation, String providerName) {
        Jdbc.update(ds, "SearchProjectionStateStore.recordActiveGeneration",
            "UPDATE core.search_projection_state SET active_generation = ?, active_provider_name = ?, "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setString(1, generation);
                ps.setString(2, providerName);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
            });
    }

    public void markDegraded(long tenantId, String searchIndex, String errorMessage) {
        Jdbc.update(ds, "SearchProjectionStateStore.markDegraded",
            "UPDATE core.search_projection_state SET status = 'degraded', retry_count = retry_count + 1, "
          + "last_error = ?, updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setString(1, errorMessage);
                ps.setLong(2, tenantId);
                ps.setString(3, searchIndex);
            });
    }

    /** Rebuild-only terminal write (design doc §3.3): the two runBuild call sites that degrade WITHOUT
     *  ever having called sink.publish (no sink resolved, or the scan/beginGeneration/publish attempt
     *  itself failed) — never used by runCatchUp, which never claims a run in the first place. */
    public void markDegradedEndingRun(long tenantId, String searchIndex, String errorMessage, UUID runId) {
        Jdbc.update(ds, "SearchProjectionStateStore.markDegradedEndingRun",
            "UPDATE core.search_projection_state SET status = 'degraded', retry_count = retry_count + 1, "
          + "last_error = ?, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'failed', last_rebuild_finished_at = now(), "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setString(1, errorMessage);
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
            });
    }

    /**
     * {@link #markDegraded}'s own twin for a failure AFTER {@code ProjectionSink.publish} already
     * succeeded, OR one FROM {@code publish} itself whose outcome is ambiguous (code review finding:
     * "publish is atomic" is the SPI's own guarantee that the switch is all-or-nothing at the
     * provider, never a promise that a caller who sees the call throw can tell a lost commit from a
     * real rollback — the ordinary shape of an HTTP provider's response dropping after it already
     * committed server-side; the reaper's own {@code reapPublishing} already treats a crash in this
     * same phase the same conservative way). Either way, whatever this row still remembers as {@code
     * live_generation}/{@code provider_name} can no longer be trusted — a later catch-up trusting it
     * would write against a generation the sink no longer recognizes as live. Clears both (the
     * {@code search_projection_state_provider_check} CHECK pairs them) so the next tick's {@code
     * dispatch()} sees {@code liveGeneration() == null} and unambiguously triggers a full rebuild
     * instead. Never use this for a failure BEFORE the {@code casToPublishing} CAS has even
     * succeeded (runBuild's earlier catch, where the OLD live_generation is still genuinely the
     * sink's live one and must be preserved, and the staging generation is still safe to abandon).
     *
     * <p>This method has two production callers (runBuild's own publish-call catch and its
     * commit-stage catch), extended in place with the run-tracking clear/stamp — widening its
     * signature carries no risk to any other path. Also clears the document count cache (design doc
     * §2): the live_generation this count belonged to is gone.
     */
    public void markDegradedClearingGeneration(long tenantId, String searchIndex, String errorMessage, UUID runId) {
        Jdbc.update(ds, "SearchProjectionStateStore.markDegradedClearingGeneration",
            "UPDATE core.search_projection_state SET status = 'degraded', retry_count = retry_count + 1, "
          + "last_error = ?, provider_name = NULL, live_generation = NULL, "
          + "last_document_count = NULL, last_document_count_at = NULL, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'failed', last_rebuild_finished_at = now(), "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setString(1, errorMessage);
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
            });
    }

    /** §3.2's pre-publish CAS: the affected-row-count IS the answer, {@code degradeIfStillBuilding}'s
     *  own idiom. One row -> this run is now past its cancellable point, safe to publish. Zero rows ->
     *  a cancel won the race; the caller abandons the staging generation and records cancelled. */
    public boolean casToPublishing(long tenantId, String searchIndex, UUID runId) {
        return Jdbc.update(ds, "SearchProjectionStateStore.casToPublishing",
            "UPDATE core.search_projection_state SET active_run_phase = 'publishing', updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND active_run_id = ? AND active_run_phase = 'building' "
          + "AND (cancel_requested_run_id IS NULL OR cancel_requested_run_id <> active_run_id)",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); ps.setObject(3, runId); }) == 1;
    }

    /** §2's own {@code status = 'cancelled'} rule: reachable only with no prior live generation to
     *  revert to. {@code last_rebuild_outcome} is always {@code 'cancelled'} regardless of which status
     *  results — {@code --wait} follows the run, not the index (design doc §2). */
    public void markCancelled(long tenantId, String searchIndex, UUID runId, boolean revertToReady) {
        Jdbc.update(ds, "SearchProjectionStateStore.markCancelled",
            "UPDATE core.search_projection_state SET status = ?, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'cancelled', last_rebuild_finished_at = now(), "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ?",
            ps -> {
                ps.setString(1, revertToReady ? "ready" : "cancelled");
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
            });
    }

    /** §3.2's between-batch check — a FRESH connection every call (never the scan's own long-running
     *  ambient transaction), so a concurrently-committed cancel request is always visible. The
     *  equality itself is pushed into SQL: {@code cancel_requested_run_id = ?} against a NULL column
     *  (the overwhelmingly common, no-cancel case) evaluates to SQL NULL, which {@link
     *  ResultSet#getBoolean} already reads back as {@code false} — no {@code Optional} unwrapping
     *  needed for a value {@link Jdbc#queryOne} would otherwise wrap as {@code Optional.of(null)}. */
    public boolean cancelRequestedFor(long tenantId, String searchIndex, UUID runId) {
        return Jdbc.queryOne(ds, "SearchProjectionStateStore.cancelRequestedFor",
                "SELECT cancel_requested_run_id = ? FROM core.search_projection_state "
              + "WHERE tenant_id = ? AND search_index = ?",
                ps -> { ps.setObject(1, runId); ps.setLong(2, tenantId); ps.setString(3, searchIndex); },
                rs -> rs.getBoolean(1))
            .orElse(false);
    }

    /** Catch-up's own "a later successful batch clears a prior degraded status" step (design doc
     *  §5c step 5, I2 fix, #671 final review): conditional on {@code status = 'degraded'} so a
     *  healthy {@code ready}/{@code building} row is never touched — same conditional-UPDATE
     *  posture as {@link #degradeIfStillBuilding}. {@code live_generation}/{@code provider_name}
     *  are left untouched; only the failure bookkeeping this transition exists to clear. */
    public void markReadyAfterCatchup(long tenantId, String searchIndex) {
        Jdbc.update(ds, "SearchProjectionStateStore.markReadyAfterCatchup",
            "UPDATE core.search_projection_state SET status = 'ready', retry_count = 0, last_error = NULL, "
          + "updated_at = now() WHERE tenant_id = ? AND search_index = ? AND status = 'degraded'",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); });
    }

    /**
     * Advances {@code position}, never backwards ({@code GREATEST}) — #712: {@code position} is a
     * monotone "highest mark id ever applied" diagnostic counter only, never a correctness gate. A
     * batch applied out of id order (a lower-id mark whose producing transaction committed after a
     * higher-id one already advanced this value) must not regress it; the mark table's own row
     * presence, not this counter, is what {@code fetchBatch} and the dispatch decision rely on.
     */
    public void advancePosition(long tenantId, String searchIndex, long position) {
        Jdbc.update(ds, "SearchProjectionStateStore.advancePosition",
            "UPDATE core.search_projection_state SET position = GREATEST(position, ?), updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ?",
            ps -> { ps.setLong(1, position); ps.setLong(2, tenantId); ps.setString(3, searchIndex); });
    }

    /** Purge-drain's own cleanup (§5d) — the row's absence means "not tracked", no soft-delete. */
    public void delete(long tenantId, String searchIndex) {
        Jdbc.update(ds, "SearchProjectionStateStore.delete",
            "DELETE FROM core.search_projection_state WHERE tenant_id = ? AND search_index = ?",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); });
    }

    /** The reaper's own candidate set (§5's D-reaper) — every row left {@code building}, cross-tenant. */
    public List<SearchProjectionState> findBuilding() {
        return Jdbc.query(ds, "SearchProjectionStateStore.findBuilding",
                String.format("SELECT %s FROM core.search_projection_state WHERE status = 'building'", COLUMNS),
                ps -> {},
                SearchProjectionStateStore::mapRow);
    }

    /** The reaper's own write: {@code ExportRunLedger.fail}'s own pattern — a plain conditional UPDATE
     *  guarded by {@code WHERE status = 'building'}, whose affected-row count IS the "did I actually
     *  degrade a still-building row" answer. Zero rows means the row already left {@code building} on
     *  its own (a legitimate finish racing the reaper) — never an error, never retried. */
    public boolean degradeIfStillBuilding(long tenantId, String searchIndex, String reason) {
        return Jdbc.update(ds, "SearchProjectionStateStore.degradeIfStillBuilding",
            "UPDATE core.search_projection_state SET status = 'degraded', last_error = ?, "
          + "retry_count = retry_count + 1, updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND status = 'building'",
            ps -> { ps.setString(1, reason); ps.setLong(2, tenantId); ps.setString(3, searchIndex); }) == 1;
    }

    /** Reaper branch 1 (§3.4): a cancel was requested but the process died before acting. Conditional
     *  on active_run_id so a run that finished in the race window between the reaper's SELECT and its
     *  own lock attempt is never downgraded (zero rows, same posture as degradeIfStillBuilding). */
    public boolean reapCancelling(long tenantId, String searchIndex, UUID runId, boolean revertToReady) {
        return Jdbc.update(ds, "SearchProjectionStateStore.reapCancelling",
            "UPDATE core.search_projection_state SET status = ?, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'cancelled', last_rebuild_finished_at = now(), "
          + "updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND active_run_phase = 'building' AND active_run_id = ?",
            ps -> {
                ps.setString(1, revertToReady ? "ready" : "cancelled");
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
                ps.setObject(5, runId);
            }) == 1;
    }

    /** Reaper branch 2 (§3.4): building, no cancellation — degradeIfStillBuilding extended with the new
     *  run-tracking columns. Never calls sink.abandon (matches shipped #671 posture). */
    public boolean reapBuilding(long tenantId, String searchIndex, UUID runId, String reason) {
        return Jdbc.update(ds, "SearchProjectionStateStore.reapBuilding",
            "UPDATE core.search_projection_state SET status = 'degraded', last_error = ?, "
          + "retry_count = retry_count + 1, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'failed', last_rebuild_finished_at = now(), "
          + "updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND active_run_phase = 'building' AND active_run_id = ?",
            ps -> {
                ps.setString(1, reason);
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
                ps.setObject(5, runId);
            }) == 1;
    }

    /** Reaper branch 3 (§3.4): publishing — deliberately conservative, reuses markDegradedClearingGeneration's
     *  own precedent (never abandons a possibly-already-live generation) but conditional on active_run_id,
     *  not a plain building-status check. */
    public boolean reapPublishing(long tenantId, String searchIndex, UUID runId, String reason) {
        return Jdbc.update(ds, "SearchProjectionStateStore.reapPublishing",
            "UPDATE core.search_projection_state SET status = 'degraded', last_error = ?, "
          + "retry_count = retry_count + 1, provider_name = NULL, live_generation = NULL, "
          + "last_document_count = NULL, last_document_count_at = NULL, "
          + "active_run_id = NULL, active_run_phase = NULL, active_run_started_at = NULL, "
          + "active_generation = NULL, active_provider_name = NULL, cancel_requested_run_id = NULL, "
          + "last_rebuild_run_id = ?, last_rebuild_outcome = 'failed', last_rebuild_finished_at = now(), "
          + "updated_at = now() "
          + "WHERE tenant_id = ? AND search_index = ? AND active_run_phase = 'publishing' AND active_run_id = ?",
            ps -> {
                ps.setString(1, reason);
                ps.setObject(2, runId);
                ps.setLong(3, tenantId);
                ps.setString(4, searchIndex);
                ps.setObject(5, runId);
            }) == 1;
    }

    /**
     * §3.2/§4's own cancel endpoint mechanism: the read and the write are ONE transaction, holding the
     * row lock continuously from SELECT to COMMIT/ROLLBACK — the true linearization point against
     * runBuild's own building->publishing CAS (Task 5). A pair with NO state row at all (never built)
     * locks nothing and is treated exactly like NoActiveRun, never a silent no-op success.
     *
     * <p>Built on {@link Jdbc#transaction(javax.sql.DataSource, String, Jdbc.TxFunction)} — the
     * shared commit/rollback/autocommit-restore ceremony every other multi-statement write in this
     * codebase already goes through, rather than one more hand-rolled twin of it.
     */
    public CancelResult requestCancel(long tenantId, String searchIndex) {
        return Jdbc.transaction(ds, "SearchProjectionStateStore.requestCancel",
            (Jdbc.TxFunction<CancelResult>) c -> requestCancel(c, tenantId, searchIndex));
    }

    private CancelResult requestCancel(Connection c, long tenantId, String searchIndex) throws SQLException {
        UUID activeRunId;
        String activeRunPhase;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT active_run_id, active_run_phase FROM core.search_projection_state "
              + "WHERE tenant_id = ? AND search_index = ? FOR UPDATE")) {
            ps.setLong(1, tenantId);
            ps.setString(2, searchIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new CancelResult.NoActiveRun(); // no row at all — Review Focus
                activeRunId = rs.getObject(1, UUID.class);
                activeRunPhase = rs.getString(2);
            }
        }
        if (activeRunId == null) return new CancelResult.NoActiveRun();
        if ("publishing".equals(activeRunPhase)) return new CancelResult.PastCancellablePoint(activeRunId);

        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core.search_projection_state SET cancel_requested_run_id = ?, updated_at = now() "
              + "WHERE tenant_id = ? AND search_index = ?")) {
            ps.setObject(1, activeRunId);
            ps.setLong(2, tenantId);
            ps.setString(3, searchIndex);
            ps.executeUpdate();
        }
        return new CancelResult.Requested(activeRunId);
    }
}
