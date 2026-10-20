package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * #748: the ledger that says which connector runs an entity's resolution pass has covered. A run is
 * uncovered for an entity when it has no {@code core.resolution_run_coverage} row for it — whatever its
 * status and whether or not its fence is known. Admin datasource, tenant bound explicitly.
 */
@ApplicationScoped
public class ResolutionCoverageStore {

    // The tenant is bound in the subquery too (not correlated to r.tenant_id): AdminDatasourceTenantGuardTest
    // wants every admin-pool statement that names a tenanted table to carry its own `tenant_id = ?`.
    // A run that never executed (skipped for a held endpoint lock) wrote nothing: never uncovered.
    // A Queued run has not started: it wrote nothing, so it is never uncovered either.
    private static final String UNCOVERED = " AND NOT r.resolution_exempt AND r.status <> '"
        + ConnectorRunStatus.QUEUED.sqlValue() + "' AND NOT EXISTS (SELECT 1 FROM core.resolution_run_coverage c"
        + " WHERE c.tenant_id = ? AND c.entity_type = ? AND c.run_id = r.id)";

    /** {@code unknown}: an uncovered run has no fence, so no bound exists. Otherwise the earliest uncovered fence, or none. */
    public record UncoveredBound(boolean unknown, Instant earliestFence) {
        public static final UncoveredBound NONE = new UncoveredBound(false, null);
    }

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    public UncoveredBound uncoveredBound(long tenantId, String entityType) {
        Jdbc.Binder bind = ps -> { ps.setLong(1, tenantId); ps.setLong(2, tenantId); ps.setString(3, entityType); };
        // A failed EXISTS read answers "unknown": the caller then stops, the conservative direction.
        boolean unknown = Jdbc.queryOne(adminDs, "ResolutionCoverageStore.uncoveredBound(unknown)",
            "SELECT EXISTS (SELECT 1 FROM connector.connector_run r"
                + " WHERE r.tenant_id = ? AND r.resolution_fence_at IS NULL" + UNCOVERED + ")",
            bind, rs -> rs.getBoolean(1)).orElse(true);
        if (unknown) return new UncoveredBound(true, null);
        return Jdbc.queryOne(adminDs, "ResolutionCoverageStore.uncoveredBound(fence)",
                "SELECT r.resolution_fence_at FROM connector.connector_run r"
                    + " WHERE r.tenant_id = ? AND r.resolution_fence_at IS NOT NULL" + UNCOVERED
                    + " ORDER BY r.resolution_fence_at LIMIT 1",
                bind, rs -> Jdbc.instant(rs, 1))
            .map(fence -> new UncoveredBound(false, fence))
            .orElse(UncoveredBound.NONE);
    }

    /** Runs already finished and not yet covered for {@code entityType} — the replay's frozen snapshot. */
    public List<UUID> finishedUncoveredRuns(long tenantId, String entityType) {
        return Jdbc.query(adminDs, "ResolutionCoverageStore.finishedUncoveredRuns",
            "SELECT r.id FROM connector.connector_run r WHERE r.tenant_id = ? AND r.status NOT IN ('"
                + ConnectorRunStatus.QUEUED.sqlValue() + "', '" + ConnectorRunStatus.RUNNING.sqlValue() + "')" + UNCOVERED,
            ps -> { ps.setLong(1, tenantId); ps.setLong(2, tenantId); ps.setString(3, entityType); },
            rs -> (UUID) rs.getObject(1));
    }

    /**
     * Covers {@code runIds} for {@code entityType}. Ids whose run no longer exists (a connector deleted
     * meanwhile cascades its runs away) or belongs to another tenant are silently skipped, so one stale id
     * can neither fail the whole batch nor lose the replay's coverage of the survivors.
     */
    public void cover(long tenantId, String entityType, Collection<UUID> runIds) {
        if (runIds.isEmpty()) return;
        Jdbc.update(adminDs, "ResolutionCoverageStore.cover",
            "INSERT INTO core.resolution_run_coverage (tenant_id, entity_type, run_id)"
                + " SELECT r.tenant_id, ?, r.id FROM connector.connector_run r"
                + " WHERE r.tenant_id = ? AND r.id = ANY(?) ON CONFLICT DO NOTHING",
            ps -> {
                ps.setString(1, entityType);
                ps.setLong(2, tenantId);
                ps.setArray(3, ps.getConnection().createArrayOf("uuid", runIds.toArray()));
            });
    }
}
