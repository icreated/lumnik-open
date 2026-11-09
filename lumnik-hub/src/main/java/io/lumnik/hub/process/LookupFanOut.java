package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.ManagedIndex;
import io.lumnik.hub.sql.ManagedIndex.State;
import io.lumnik.hub.sql.TemporalText;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * The durable fan-out of a change in a LOOKUP table of an Entity (#1834): a recalibration in an instrument register
 * re-evaluates every result of that instrument. The number of rows reached is the business meaning and is never capped;
 * the WORK is bounded: {@link ProcessConsumer} writes one row to {@code core.process_fanout} and returns (its cursor
 * advances at once), and this worker takes the row a page of canonical keys at a time.
 *
 * <p><b>One page, one transaction.</b> The page claims its row ({@code FOR UPDATE SKIP LOCKED}, as the outbox drain, so
 * several nodes share the queue), reads the next keys after the row's cursor, reads their fused rows under RLS, evaluates,
 * writes the decisions and advances the cursor. The claim, the decisions and the cursor are ONE transaction on the admin
 * pool; the fused rows are read just before, in a read-only transaction of their own on the {@code readonly} pool
 * ({@link FusedRowReader#readAll}), which writes nothing — so a page that fails after the read leaves nothing behind. A
 * crash resumes at the next page; a page
 * replayed (committed, the node died before moving on) emits nothing twice, the decision's dedup key being
 * {@code (process, event, canonical key)}. The cursor is a keyset on the canonical key, never an {@code OFFSET}.
 *
 * <p><b>The index is the precondition.</b> The reverse read ({@link LookupReach#pageSql}) is by the join key; without
 * {@link LookupReach#index()} it scans the source for every page. A row whose index is not valid waits
 * ({@code waiting-index}) and is drained once it is: nothing is lost, nothing is refused for lack of one.
 *
 * <p><b>Two datasources, as the source path.</b> The keys are derived on {@code admin}, the tenant bound in the predicate;
 * they are candidates. The fused row is read on {@code readonly} under the tenant's RLS ({@link FusedRowReader}), and that
 * read decides what exists.
 */
@ApplicationScoped
public class LookupFanOut {

    private static final Logger LOG = Logger.getLogger(LookupFanOut.class);

    /** A row that fails this many times stops being claimed and says why ({@code failed}); nothing retries it silently for ever. */
    static final int MAX_ATTEMPTS = 5;

    private static final String ENQUEUE_SQL =
        "INSERT INTO core.process_fanout (tenant_id, process_id, source_event_id, impact_target, lookup_key) "
        + "SELECT ?, id, ?, ?, ? FROM core.process_manifest WHERE tenant_id = ? AND name = ? "
        + "ON CONFLICT (process_id, source_event_id, impact_target) DO NOTHING";

    private static final String ADVANCE_SQL = "UPDATE core.process_fanout SET after_canonical_key = ?, processed_count = processed_count + ?, "
        + "status = ?, attempts = 0, last_error = NULL, updated_at = now() WHERE id = ? AND tenant_id = ?";

    private static final String CLAIM_SQL =
        "SELECT f.id, f.tenant_id, f.source_event_id, f.impact_target, f.lookup_key, f.after_canonical_key, f.status, "
        + "f.started_at IS NOT NULL, f.processed_count, p.yaml_text, p.enabled, p.on_table "
        + "FROM core.process_fanout f JOIN core.process_manifest p ON p.id = f.process_id "
        + "WHERE f.status IN ('pending', 'waiting-index', 'running') AND f.attempts < " + MAX_ATTEMPTS
        + " AND p.enabled AND f.updated_at <= now() - make_interval(secs => f.attempts * 10) AND f.id <> ALL (?) ORDER BY f.id LIMIT 1 FOR UPDATE OF f SKIP LOCKED";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ProcessConsumer consumer;
    @Inject ProcessManifestStore store;
    @Inject FusedRowReader fused;
    @Inject MatchIndexes matchIndexes;
    @Inject ManagedIndex indexes;

    @ConfigProperty(name = "lumnik.process.fanout.page-size", defaultValue = "500")
    int pageSize;

    /** How long a finished row is kept for its numbers before it is purged. */
    @ConfigProperty(name = "lumnik.process.fanout.keep-done", defaultValue = "P7D")
    Duration keepDone;

    @Scheduled(every = "${lumnik.process.fanout.every:2s}", concurrentExecution = SKIP)
    void tick() {
        try {
            TenantContext.runAsSystem(() -> {
                drain(pageSize);
                purge();
            });
        } catch (RuntimeException e) {
            LOG.error("LookupFanOut: a pass failed; the next one retries", e);
        }
    }

    /** Tries of the hand-off: the outbox sweeper moves its cursor past an event whatever the consumer threw. */
    private static final int ENQUEUE_TRIES = 4;

    /**
     * Queues the fan-out of one lookup event for one process; idempotent on {@code (process, event, target)}.
     *
     * <p>This is the one step where the change is not yet durable, and {@code OutboxSweeper} never redelivers an event: a
     * consumer owns its retry. So the insert is tried a few times, a moment apart, before it gives up — and when it gives
     * up it says which change is lost, with everything needed to queue it again. A longer outage is the
     * delivery question the outbox itself answers for every consumer (#1837).
     */
    void enqueue(long tenantId, String process, long eventId, String target, String lookupKey) {
        for (int attempt = 1; ; attempt++) {
            try {
                Jdbc.update(adminDs, "LookupFanOut.enqueue", ENQUEUE_SQL, ps -> {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, eventId);
                    ps.setString(3, target);
                    ps.setString(4, lookupKey);
                    ps.setLong(5, tenantId);
                    ps.setString(6, process);
                });
                return;
            } catch (RuntimeException e) {
                if (attempt == ENQUEUE_TRIES) {
                    LOG.errorf(e, "LookupFanOut: the fan-out of event %d for process '%s' (tenant %d, target %s) could not be queued"
                        + " — its fused rows are NOT re-evaluated", eventId, process, tenantId, target);
                    throw e;
                }
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Pages until nothing is claimable. A row waiting for its index is left for the next pass, not claimed again in this one. */
    public void drain(int size) {
        Set<Long> parked = new HashSet<>();
        Optional<Paged> paged;
        do {
            paged = page(size, parked);
            paged.filter(Paged::parked).ifPresent(p -> parked.add(p.id()));
        } while (paged.isPresent());
    }

    /** One page of the oldest claimable row; false when there was none. Exposed for tests: the worker is driven page by page. */
    public boolean page(int size) {
        return page(size, Set.of()).isPresent();
    }

    /** What a pass did with the row it claimed: {@code parked} when it waits for its index. */
    private record Paged(long id, boolean parked) {}

    /** @return empty when nothing was claimable */
    private Optional<Paged> page(int size, Set<Long> parked) {
        Work[] claimed = {null};
        try {
            return Jdbc.transaction(adminDs, "LookupFanOut.page", c -> {
                Optional<Work> work = claim(c, parked);
                if (work.isEmpty()) return Optional.<Paged>empty();
                claimed[0] = work.get();
                return Optional.of(new Paged(work.get().id(), step(c, work.get(), size)));
            });
        } catch (RuntimeException e) {
            if (claimed[0] == null) throw e;   // not about a row: the pass says so
            recordFailure(claimed[0], e);
            return Optional.of(new Paged(claimed[0].id(), true));   // not claimed again in this pass: the next tick retries
        }
    }

    /** A claimed row, with the process it belongs to. */
    private record Work(long id, long tenantId, long eventId, String target, String lookupKey, String after,
                        boolean started, long processed, ProcessManifestStore.StoredProcess process) {}

    private Optional<Work> claim(Connection c, Set<Long> parked) throws SQLException {
        Array excluded = c.createArrayOf("bigint", parked.isEmpty() ? new Long[] {0L} : parked.toArray());
        try {
            return Jdbc.queryOne(c, "LookupFanOut.claim", CLAIM_SQL, ps -> ps.setArray(1, excluded), rs -> new Work(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBoolean(8), rs.getLong(9), store.stored(rs.getString(10), rs.getBoolean(11))));
        } finally {
            excluded.free();
        }
    }

    /** @return true when the row is parked (waiting for its index), false when a page was done */
    private boolean step(Connection c, Work w, int size) throws SQLException {
        EntityManifest entity = matchIndexes.storedEntity(w.process().onTable()).orElse(null);
        EntityViewCompiler.Sources catalog = entity == null ? null : fused.catalog(entity);
        LookupReach reach = entity == null ? null
            : LookupReach.of(entity, catalog).stream().filter(r -> r.target().equals(w.target())).findFirst().orElse(null);
        if (reach == null) {
            // the Entity was re-applied without this join: the work has no meaning any more, and says so
            setStatus(c, w, "failed", "the Entity no longer joins " + w.target());
            return false;
        }
        if (indexes.state(reach.index()) != State.VALID) {
            setStatus(c, w, "waiting-index", "waiting for the index " + reach.index().name() + " on " + reach.sourceTable());
            return true;
        }
        TemporalText.utcSession(c);   // a key's text in UTC, as the view casts it (#1325)
        if (!w.started()) start(c, w, reach);

        List<List<String>> keys = pageOfKeys(c, w, reach, size);
        if (!keys.isEmpty()) evaluate(c, w, entity, catalog, keys);
        String last = keys.isEmpty() ? w.after() : keys.get(keys.size() - 1).get(0);
        boolean done = keys.size() < size;
        Jdbc.update(c, "LookupFanOut.advance",
            ADVANCE_SQL, ps -> {
                ps.setString(1, last);
                ps.setLong(2, keys.size());
                ps.setString(3, done ? "done" : "running");
                ps.setLong(4, w.id());
                ps.setLong(5, w.tenantId());
            });
        if (done) logDone(c, w);
        return false;
    }

    /** The first page: the keys it will reach, counted once — what "pages left" is computed from, for display only. */
    private void start(Connection c, Work w, LookupReach reach) {
        long affected = Jdbc.queryOne(c, "LookupFanOut.count", reach.countSql(), ps -> bindReach(ps, w), rs -> rs.getLong(1)).orElse(0L);
        Jdbc.update(c, "LookupFanOut.start",
            "UPDATE core.process_fanout SET started_at = now(), status = 'running', affected_count = ? WHERE id = ? AND tenant_id = ?", ps -> {
                ps.setLong(1, affected);
                ps.setLong(2, w.id());
                ps.setLong(3, w.tenantId());
            });
    }

    private static void bindReach(PreparedStatement ps, Work w) throws SQLException {
        ps.setLong(1, w.tenantId());
        ps.setString(2, w.lookupKey());
    }

    /** The next keys after the cursor: each is {@code [canonical, match value…]}. */
    private List<List<String>> pageOfKeys(Connection c, Work w, LookupReach reach, int size) {
        boolean after = w.after() != null;
        return Jdbc.query(c, "LookupFanOut.keys", reach.pageSql(after), ps -> {
            bindReach(ps, w);
            int next = 3;
            if (after) ps.setString(next++, w.after());
            ps.setInt(next, size);
        }, rs -> {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= reach.matchKeys().size() + 1; i++) row.add(rs.getString(i));
            return row;
        });
    }

    /** Reads the page's fused rows under RLS and decides, writing the decisions on the claim's connection. */
    private void evaluate(Connection c, Work w, EntityManifest entity, EntityViewCompiler.Sources catalog,
                          List<List<String>> keys) {
        FusedRowReader.FusedView view = new FusedRowReader.FusedView(w.process().onTable(), entity.match(),
            EntityViewCompiler.matchColumnTypes(entity, catalog));
        List<FusedRowReader.FusedKey> fusedKeys = keys.stream()
            .map(k -> new FusedRowReader.FusedKey(k.subList(1, k.size()))).toList();
        Map<String, Map<String, Object>> rows = fused.readAll(view, fusedKeys, w.tenantId());
        ProcessConsumer.Rule rule = ProcessConsumer.Rule.of(w.process());
        ProcessConsumer.DecisionSink sink = ProcessConsumer.onConnection(c);
        for (FusedRowReader.FusedKey key : fusedKeys) {
            Map<String, Object> content = rows.get(key.canonical());
            if (content != null) consumer.decideOnFused(sink, w.tenantId(), w.eventId(), rule, content, view.view(), key.canonical());
        }
    }

    /** A status and the reason for it (null clears it), on the claimed row. */
    private void setStatus(Connection c, Work w, String status, String why) {
        Jdbc.update(c, "LookupFanOut.status",
            "UPDATE core.process_fanout SET status = ?, last_error = ?, updated_at = now() WHERE id = ? AND tenant_id = ?", ps -> {
                ps.setString(1, status);
                ps.setString(2, why);
                ps.setLong(3, w.id());
                ps.setLong(4, w.tenantId());
            });
    }

    private void logDone(Connection c, Work w) {
        Jdbc.queryOne(c, "LookupFanOut.done", "SELECT affected_count, processed_count, "
            + "extract(epoch FROM now() - started_at) FROM core.process_fanout WHERE id = ? AND tenant_id = ?",
            ps -> { ps.setLong(1, w.id()); ps.setLong(2, w.tenantId()); },
            rs -> {
                LOG.infof("process fan-out done: process '%s', event %d, %s — %d keys reached, %d evaluated, %.1fs",
                    w.process().name(), w.eventId(), w.target(), rs.getLong(1), rs.getLong(2), rs.getDouble(3));
                return true;
            });
    }

    /** A failed page rolled back with its transaction: say it on the row, in a transaction of its own, and count the attempt. */
    private void recordFailure(Work w, RuntimeException e) {
        LOG.warnf(e, "LookupFanOut: the page of work row %d failed — it is tried again", w.id());
        try {
            Jdbc.update(adminDs, "LookupFanOut.failure",
                "UPDATE core.process_fanout SET attempts = attempts + 1, last_error = ?, updated_at = now(), "
                + "status = CASE WHEN attempts + 1 >= ? THEN 'failed' ELSE status END WHERE id = ? AND tenant_id = ?", ps -> {
                    ps.setString(1, Jdbc.failureText("LookupFanOut.page", e));
                    ps.setInt(2, MAX_ATTEMPTS);
                    ps.setLong(3, w.id());
                    ps.setLong(4, w.tenantId());
                });
        } catch (RuntimeException again) {
            LOG.warnf(again, "LookupFanOut: the failure of work row %d could not be recorded", w.id());
        }
    }

    /** Finished rows are kept for their numbers ({@code affected}, {@code processed}, the span), then removed. */
    void purge() {
        Jdbc.update(adminDs, "LookupFanOut.purge", "DELETE FROM core.process_fanout WHERE status = 'done' AND updated_at < now() - make_interval(secs => ?)",
            ps -> ps.setLong(1, keepDone.toSeconds()));
    }
}
