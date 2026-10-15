package io.lumnik.hub.export.run;

import io.lumnik.hub.export.SnapshotStats;
import io.lumnik.hub.export.destination.PublishedArtifact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code connector.export_run} delivery ledger (V54). Same posture as ExportManifestStore:
 * every method is {@code @Transactional(MANDATORY)} and runs on the request's own RLS-enforced
 * connection (Session.doReturningWork), so tenant isolation is a property of the connection and a
 * missing transaction fails loudly instead of reading back empty.
 *
 * <p>The ledger does not decide who may run; the database does. {@link #claim} is an INSERT that
 * its three unique indexes may turn into nothing ("already in flight", "occurrence already claimed
 * or delivered"; a delivered occurrence is also refused up front, the delivered index staying the
 * backstop), and {@link #complete}/{@link #fail} only move a row that is still {@code Running}, so a reaped
 * run's late completion cannot resurrect it (the connector-run {@code finalizeRun} rule).
 */
@ApplicationScoped
public class ExportRunLedger {

    private static final String QUEUED = ExportRunStatus.QUEUED.sqlValue();
    private static final String RUNNING = ExportRunStatus.RUNNING.sqlValue();
    private static final String COMPLETED = ExportRunStatus.COMPLETED.sqlValue();

    private static final String COLUMNS =
        "id, export_name, scope, entity_name, destination_kind, trigger, triggered_by, occurrence, "
      + "attempt, retry_of, status, started_at, ended_at, row_count, byte_count, sha256, location, "
      + "version_id, etag, failure_code, failure_reason";

    /** Everything a run freezes at claim time. {@code occurrence} is non-null iff SCHEDULE. */
    public record NewRun(long tenantId, UUID exportId, String exportName, String scope,
                         String entityName, String destinationKind, String manifestSha256,
                         ExportTrigger trigger, Long triggeredBy, Instant occurrence,
                         Instant artifactTime, int attempt, UUID retryOf) {}

    /** The outcome of {@link #claim}: only {@link Claimed} means this caller now owns a run. */
    public sealed interface Claim permits Claimed, AlreadyRunning, OccurrenceTaken {}

    public record Claimed(UUID runId) implements Claim {}

    /** Another run of this export is in flight; {@code runId} is that run. */
    public record AlreadyRunning(UUID runId) implements Claim {}

    /** This occurrence was already claimed at this attempt, or already delivered. */
    public record OccurrenceTaken() implements Claim {}

    @Inject EntityManager em;

    private Session session() {
        return em.unwrap(Session.class);
    }

    /**
     * Insert a {@code Running} row — or, for the queue (#952), a {@code Queued} one: no start, an accept
     * instant — or say why not. {@code ON CONFLICT DO NOTHING} names no
     * conflict target, so every unique index arbitrates and a refusal is an empty RETURNING, not
     * an aborted transaction. A retry's {@code root_run_id} is read from its original here: the
     * original's own root, or the original itself when it is a lineage's first run.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Claim claim(NewRun run) {
        return claim(run, false);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Claim claim(NewRun run, boolean queued) {
        String name = run.exportName().toLowerCase(Locale.ROOT);
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO connector.export_run (tenant_id, export_id, export_name, scope, "
                  + "entity_name, destination_kind, manifest_sha256, trigger, triggered_by, "
                  + "occurrence, artifact_time, attempt, retry_of, root_run_id, status, started_at, queued_at) "
                  + "SELECT ?::bigint, ?::uuid, ?, ?, ?, ?, ?, ?, ?::bigint, ?::timestamptz, "
                  + "?::timestamptz, ?::int, ?::uuid, "
                  + "(SELECT COALESCE(o.root_run_id, o.id) FROM connector.export_run o "
                  + " WHERE o.tenant_id = ? AND o.id = ?::uuid), ?, "
                  + "CASE WHEN ?::boolean THEN NULL ELSE now() END, "
                  + "CASE WHEN ?::boolean THEN now() ELSE NULL END "
                  + "WHERE NOT EXISTS (SELECT 1 FROM connector.export_run WHERE tenant_id = ? "
                  + "AND export_name = ? AND occurrence = ?::timestamptz AND status = ?) "
                  + "ON CONFLICT DO NOTHING RETURNING id")) {
                ps.setLong(1, run.tenantId());
                ps.setObject(2, run.exportId());
                ps.setString(3, name);
                ps.setString(4, run.scope());
                ps.setString(5, run.entityName());
                ps.setString(6, run.destinationKind());
                ps.setString(7, run.manifestSha256());
                ps.setString(8, run.trigger().sqlValue());
                if (run.triggeredBy() == null) ps.setNull(9, Types.BIGINT);
                else ps.setLong(9, run.triggeredBy());
                ps.setObject(10, utc(run.occurrence()));
                ps.setObject(11, utc(run.artifactTime()));
                ps.setInt(12, run.attempt());
                ps.setObject(13, run.retryOf());
                ps.setLong(14, run.tenantId());
                ps.setObject(15, run.retryOf());
                ps.setString(16, queued ? QUEUED : RUNNING);
                ps.setBoolean(17, queued);
                ps.setBoolean(18, queued);
                ps.setLong(19, run.tenantId());
                ps.setString(20, name);
                ps.setObject(21, utc(run.occurrence()));
                ps.setString(22, COMPLETED);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Claimed(rs.getObject(1, UUID.class));
                }
            }
            return refusal(conn, run.tenantId(), name, run.occurrence());
        });
    }

    /**
     * Why a claim inserted nothing. A delivered occurrence first (the up-front NOT EXISTS, or the
     * delivered index); then a run in flight, named; anything else is this occurrence's attempt
     * already taken. The in-flight run may finish between the INSERT and this read; then there is
     * nothing to point at. For a scheduled occurrence that reads as a slot lost to a race; a run
     * with no occurrence (manual, or a manual retry) has no slot to lose, so it stays
     * {@code AlreadyRunning}, unnamed, and never reads as "already delivered".
     */
    private static Claim refusal(Connection conn, long tenantId, String name, Instant occurrence)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM connector.export_run WHERE tenant_id = ? "
              + "  AND export_name = ? AND occurrence = ?::timestamptz AND status = ?), "
              + "(SELECT id FROM connector.export_run WHERE tenant_id = ? AND export_name = ? "
              + "  AND status IN (?, ?))")) {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            ps.setObject(3, utc(occurrence));
            ps.setString(4, COMPLETED);
            ps.setLong(5, tenantId);
            ps.setString(6, name);
            ps.setString(7, QUEUED);
            ps.setString(8, RUNNING);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID running = rs.getObject(2, UUID.class);
                if (rs.getBoolean(1)) return new OccurrenceTaken();
                if (running == null && occurrence != null) return new OccurrenceTaken();
                return new AlreadyRunning(running);
            }
        }
    }

    /** Record the delivery receipt. False when the row is no longer Running (reaped or failed). */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean complete(UUID runId, Instant endedAt, SnapshotStats stats,
                            PublishedArtifact artifact) {
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE connector.export_run SET status = ?, ended_at = ?, row_count = ?, "
                  + "byte_count = ?, sha256 = ?, location = ?, version_id = ?, etag = ? "
                  + "WHERE id = ? AND status = ?")) {
                ps.setString(1, COMPLETED);
                ps.setObject(2, utc(endedAt));
                ps.setLong(3, stats.rowCount());
                ps.setLong(4, stats.byteCount());
                ps.setString(5, stats.sha256Hex());
                ps.setString(6, artifact.location());
                ps.setString(7, artifact.versionId());
                ps.setString(8, artifact.etag());
                ps.setObject(9, runId);
                ps.setString(10, RUNNING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Record why a run failed. False when the row is no longer Running. {@code reason} must
     *  already be a sanitized, caller-built sentence — never a raw third-party message. */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean fail(UUID runId, Instant endedAt, ExportFailureCode code, String reason) {
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE connector.export_run SET status = ?, ended_at = ?, failure_code = ?, "
                  + "failure_reason = ? WHERE id = ? AND status = ?")) {
                ps.setString(1, ExportRunStatus.FAILED.sqlValue());
                ps.setObject(2, utc(endedAt));
                ps.setString(3, code.sqlValue());
                ps.setString(4, reason);
                ps.setObject(5, runId);
                ps.setString(6, RUNNING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** One run of this export in this tenant; empty when the id belongs to another export. */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<ExportRunView> find(long tenantId, String exportName, UUID runId) {
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM connector.export_run "
                  + "WHERE tenant_id = ? AND export_name = ? AND id = ?")) {
                ps.setLong(1, tenantId);
                ps.setString(2, exportName.toLowerCase(Locale.ROOT));
                ps.setObject(3, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(view(rs)) : Optional.<ExportRunView>empty();
                }
            }
        });
    }

    /** Newest first; {@code limit} is the caller's already-clamped page size. */
    @Transactional(Transactional.TxType.MANDATORY)
    public List<ExportRunView> list(long tenantId, String exportName, int limit) {
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM connector.export_run "
                  + "WHERE tenant_id = ? AND export_name = ? ORDER BY started_at DESC, id LIMIT ?")) {
                ps.setLong(1, tenantId);
                ps.setString(2, exportName.toLowerCase(Locale.ROOT));
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ExportRunView> out = new ArrayList<>();
                    while (rs.next()) out.add(view(rs));
                    return out;
                }
            }
        });
    }

    /** What a retry of a run needs: the run's status (only a Failed one is retryable), the
     *  trigger, occurrence and artifact time it inherits, and the next attempt of its lineage. */
    public record RetrySeed(ExportRunStatus status, ExportTrigger trigger, Instant occurrence,
                            Instant artifactTime, int nextAttempt) {}

    /**
     * The seed for retrying {@code runId}. Its lineage is every run sharing its
     * {@code COALESCE(root_run_id, id)} — the original and all its retries, however deep —
     * and {@code nextAttempt} is one past the highest attempt there. Empty when the id is not
     * one of this export's runs.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<RetrySeed> retrySeed(long tenantId, String exportName, UUID runId) {
        return session().doReturningWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT e.status, e.trigger, e.occurrence, e.artifact_time, "
                  + "(SELECT max(l.attempt) FROM connector.export_run l WHERE l.tenant_id = e.tenant_id "
                  + "  AND COALESCE(l.root_run_id, l.id) = COALESCE(e.root_run_id, e.id)) "
                  + "FROM connector.export_run e WHERE e.tenant_id = ? AND e.export_name = ? AND e.id = ?")) {
                ps.setLong(1, tenantId);
                ps.setString(2, exportName.toLowerCase(Locale.ROOT));
                ps.setObject(3, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<RetrySeed>empty();
                    return Optional.of(new RetrySeed(ExportRunStatus.fromSql(rs.getString(1)),
                        ExportTrigger.fromSql(rs.getString(2)), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), rs.getInt(5) + 1));
                }
            }
        });
    }

    private static ExportRunView view(ResultSet rs) throws SQLException {
        String code = rs.getString(20);
        return new ExportRunView(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), ExportTrigger.fromSql(rs.getString(6)), (Long) rs.getObject(7),
            instant(rs.getTimestamp(8)), rs.getInt(9), rs.getObject(10, UUID.class),
            ExportRunStatus.fromSql(rs.getString(11)), instant(rs.getTimestamp(12)),
            instant(rs.getTimestamp(13)), (Long) rs.getObject(14), (Long) rs.getObject(15),
            rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19),
            code == null ? null : ExportFailureCode.fromSql(code), rs.getString(21));
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
