package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * #701 phase 6, design doc §5: the reconciliation sweep that closes the residual gap
 * {@link SearchProjectionRunHook}'s own javadoc documents — a table whose only ingestion run was
 * reaped, with no later run on that table to piggyback on, never fires a {@code ConnectorRunCompleted}
 * that would trigger a scan. Marking must never be on the correctness path of ingestion (issue's own
 * scope line); this sweep is what makes that literally true even if every event listener were skipped.
 *
 * <p>Discovers its work cross-tenant from {@code core.search_index} (via
 * {@link SearchProjectionMarkStore#tenantsWithValidIndex}), then reuses the SAME scan-then-mark-then-advance
 * machinery {@link SearchProjectionRunHook} already implements — {@link SearchProjectionRunHook#targetsByTable(long)}
 * for table discovery (SearchIndex &rarr; entity &rarr; {@code EntityManifest.sources()}, never the
 * watermark table alone — a table whose only run was reaped never has a watermark row, so a
 * watermark-only sweep could never find it) and {@link SearchProjectionRunHook#processTable(long, String, List)}
 * for the scan/mark/advance itself (identical predicate, {@code _ingested_at > watermark}, absent
 * watermark treated as -infinity). No scan/mark SQL is duplicated here — this class is pure
 * discovery-and-fan-out over that shared logic, on a timer instead of a
 * {@code ConnectorRunCompleted} event.
 *
 * <p>Catch-and-log is PER (tenant, table) pair, not per sweep tick: unlike
 * {@link SearchProjectionRunHook#onRunCompleted}, which scopes its catch to the whole event (one
 * table's failure there is acceptable — it is one tenant's one triggering run, and the next run or
 * this very sweep repairs it), a reconciler tick that abandoned every remaining tenant/table after the
 * first failure would defeat the point of being the safety net. Each pair gets its own try/catch so
 * one bad table never stops the rest of the same tick from being checked.
 *
 * <p>Same cadence and cross-tenant admin-pool idiom as {@code ConnectorScheduler}: ticks every
 * minute, {@code concurrentExecution = SKIP} so a slow tick never overlaps the next.
 *
 * <p>Before a table's first scan it gets its {@code (tenant_id, _ingested_at)} index ({@link IngestedAtIndex}, #945).
 *
 * <p>A tick does nothing without a Search provider (#896, see {@link SearchProjectionRunHook}); {@link #sweep()}
 * itself does not ask the gate.
 */
@ApplicationScoped
public class SearchProjectionReconciler {

    private static final Logger LOG = Logger.getLogger(SearchProjectionReconciler.class);

    @Inject @DataSource("admin") AgroalDataSource ds;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionRunHook runHook;
    @Inject IngestedAtIndex ingestedAtIndex;
    @Inject ProjectionSinkGate sinkGate;

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void scheduledTick() {
        try {
            if (sinkGate.resolve().isEmpty()) return;
            sweep();
        } catch (Exception e) {
            LOG.error("SearchProjectionReconciler.sweep() failed; will retry in 1m", e);
        }
    }

    /** Exposed for tests: one full cross-tenant pass. */
    public void sweep() throws SQLException {
        List<Long> tenantIds;
        try (Connection c = ds.getConnection()) {
            tenantIds = markStore.tenantsWithValidIndex(c);
        }
        for (Long tenantId : tenantIds) {
            sweepTenant(tenantId);
        }
    }

    private void sweepTenant(long tenantId) {
        Map<String, List<SearchProjectionRunHook.TableTarget>> targetsByTable;
        try {
            targetsByTable = runHook.targetsByTable(tenantId);
        } catch (Exception e) {
            LOG.warnf(e, "SearchProjectionReconciler: discovery failed for tenant %s — "
                + "will retry next tick", tenantId);
            return;
        }
        for (Map.Entry<String, List<SearchProjectionRunHook.TableTarget>> e : targetsByTable.entrySet()) {
            String tableName = e.getKey();
            try {
                ingestedAtIndex.ensure(tableName);
            } catch (Exception ex) {
                // The scan is correct without the index, only slow: never let it skip the table.
                LOG.warnf(ex, "SearchProjectionReconciler: indexing %s failed — scanning without it, "
                    + "will retry next tick", tableName);
            }
            try {
                runHook.processTable(tenantId, tableName, e.getValue());
            } catch (Exception ex) {
                LOG.warnf(ex, "SearchProjectionReconciler: sweep failed for tenant %s table %s — "
                    + "will retry next tick", tenantId, tableName);
            }
        }
    }
}
