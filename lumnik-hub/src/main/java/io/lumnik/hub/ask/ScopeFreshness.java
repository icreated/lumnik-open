package io.lumnik.hub.ask;

import static io.lumnik.hub.connector.domain.ConnectorRunStatus.COMPLETED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.PARTIAL;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The freshness dial (P5): when did the scope's ground last change hands? The signal is the
 * RUN HISTORY — MAX(ended_at) over the scope's connectors' completed runs — never a MAX over
 * million-row mirror tables (no index on _ingested_at; a seq scan per ask would not survive
 * production volume). Admin datasource (small cross-schema join): the explicit tenant_id
 * filters ARE the guard — same contract as WorkflowObservationStore. The lateral reads the runs
 * itself, so it binds the caller's tenant too rather than trusting the correlation alone.
 *
 * <p>Each connector answers with its newest landed run from the head of
 * {@code idx_run_connector_ended} (V63, #907). {@code ended_at IS NOT NULL} keeps MAX's
 * semantics: DESC sorts NULLs first, and a run without an end time dates nothing.
 */
@ApplicationScoped
public class ScopeFreshness {

    static final String SQL =
        "SELECT MAX(newest.ended_at) FROM connector.connector c " +
        "CROSS JOIN LATERAL (SELECT r.ended_at FROM connector.connector_run r " +
        "WHERE r.tenant_id = ? AND r.connector_id = c.id AND r.status IN ('" + COMPLETED.sqlValue() + "', '" + PARTIAL.sqlValue() + "') " +
        "AND r.ended_at IS NOT NULL ORDER BY r.ended_at DESC LIMIT 1) newest " +
        "WHERE c.tenant_id = ? AND ? = ANY(c.tags) AND c.deleted_at IS NULL";

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /** Empty when the scope has no run that LANDED data (fresh install, one-shot-only) — the caller
     *  surfaces absence, never a fake timestamp.
     *
     *  <p>Partial counts, and must: such a run wrote everything it read and simply did not read
     *  everything there was. On a topic whose retention keeps overtaking the cursor — the very
     *  condition Partial exists to report — EVERY run ends Partial, and excluding them would print
     *  a stale "data as of" (or nothing) while fresh rows land on every run. */
    public Optional<Instant> lastCompletedRun(long tenantId, String scope) {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setLong(1, tenantId);   // the runs
            ps.setLong(2, tenantId);   // the connectors
            ps.setString(3, scope);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
            }
        } catch (Exception e) {
            // Freshness must never fail an answer — absent beats broken.
            return Optional.empty();
        }
    }
}
