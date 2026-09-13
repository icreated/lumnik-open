package io.lumnik.hub.ask;

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
 * filter IS the guard — same contract as WorkflowObservationStore.
 */
@ApplicationScoped
public class ScopeFreshness {

    private static final String SQL =
        "SELECT MAX(r.ended_at) FROM connector.connector_run r " +
        "JOIN connector.connector c ON c.id = r.connector_id " +
        "WHERE c.tenant_id = ? AND ? = ANY(c.tags) AND c.deleted_at IS NULL AND r.status IN ('Completed', 'Partial')";

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
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
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
