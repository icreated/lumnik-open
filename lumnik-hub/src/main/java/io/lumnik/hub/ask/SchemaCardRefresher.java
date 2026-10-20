package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-refreshes the ask-to-SQL SchemaCard after a connector run that changed the hub schema.
 * Probe: connector.dynamic_table rows touched since the run started (the writer updates the registry
 * at table creation AND column evolution). The common incremental run costs one SELECT and returns.
 * Failures are logged and swallowed — the run is already terminal; {@code lm source rediscover} remains the
 * manual fallback. Runs on a CDI worker thread, so DB access establishes system mode; rediscover
 * holds no transaction across its reads of the source (#938).
 */
@ApplicationScoped
public class SchemaCardRefresher {

    private static final Logger LOG = Logger.getLogger(SchemaCardRefresher.class);
    // Slack covers app-server vs DB clock skew (runStartedAt is a JVM Instant,
    // dynamic_table.updated_at is DB now()). On separate hosts a real schema change can land just
    // before runStartedAt and be MISSED with ZERO slack. Erring toward a rare, harmless, idempotent
    // extra rediscover is correct; MISSING a real schema change is not.
    private static final Duration CLOCK_SLACK = Duration.ofSeconds(2);

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject DiscoveryService discovery;
    @Inject DynamicTableRegistry dynamicTables;
    @Inject TransactionSynchronizationRegistry txSync;

    void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
        try {
            if (!schemaChangedDuring(ev)) return;
            // every writer of its tables, not only itself: a second writer changes the first one's card (#1186)
            for (String tag : dynamicTables.scopesOfTablesWrittenBy(ev.tenantId(), ev.connectorId())) {
                LOG.infof("SchemaCardRefresher: schema changed during run %s — rediscovering scope '%s' (tenant %d)",
                    ev.runId(), tag, ev.tenantId());
                TenantContext.callAsSystem(() -> discovery.rediscover(ev.tenantId(), tag));
            }
        } catch (Exception e) {
            LOG.warnf(e, "SchemaCardRefresher failed for run %s — manual `lm source rediscover` remains available",
                ev.runId());
        }
    }

    /**
     * A connector delete changes which writers its shared tables have, and a card shows a table only
     * while every writer carries its scope (#1186): rebuild these scopes' cards once the delete has
     * committed, so the remaining writers' métiers see the table again. Best-effort, as after a run.
     */
    public void afterCommit(long tenantId, List<String> scopes) {
        if (scopes.isEmpty()) return;
        txSync.registerInterposedSynchronization(new Synchronization() {
            @Override public void beforeCompletion() { /* nothing to flush */ }
            @Override public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) return;
                for (String scope : scopes) {
                    try {
                        // The committed transaction is still associated with the thread here, and the
                        // credential resolver cannot join it: suspend it, as the one-shot CSV ingest does (#938).
                        TenantContext.callAsSystem(() ->
                            QuarkusTransaction.suspendingExisting().call(() -> discovery.rediscover(tenantId, scope)));
                    } catch (RuntimeException e) {
                        LOG.warnf(e, "SchemaCardRefresher: card refresh failed for scope %s — manual `lm source rediscover` remains available", scope);
                    }
                }
            }
        });
    }

    /**
     * A connector's scopes changed: its tables leave the cards of the scopes it left and join the
     * cards of those it entered, once the write has committed (#1194). A scope it kept is untouched —
     * a card holds a table while every writer carries its scope, and that answer did not move there.
     */
    public void afterRetag(long tenantId, List<String> before, List<String> after) {
        List<String> was = before != null ? before : List.of();   // a connector's tags may be null
        Set<String> changed = new LinkedHashSet<>(was);
        changed.addAll(after);
        changed.removeIf(s -> was.contains(s) && after.contains(s));
        afterCommit(tenantId, List.copyOf(changed));
    }

    private boolean schemaChangedDuring(ConnectorRunCompleted ev) {
        Instant since = ev.runStartedAt().minus(CLOCK_SLACK);
        return Jdbc.exists(adminDs, "SchemaCardRefresher.schemaChangedDuring",
            // tenant_id is redundant with a UUID connector id, and stays: this runs on the admin
            // datasource, where nothing else scopes a read, and the guard over that datasource
            // asks every query naming a tenanted table to say whose rows it means.
            "SELECT 1 FROM connector.dynamic_table WHERE tenant_id = ? AND connector_id = ? "
            + "AND updated_at >= ? LIMIT 1",
            ps -> { ps.setLong(1, ev.tenantId()); ps.setObject(2, ev.connectorId());
                    ps.setTimestamp(3, Timestamp.from(since)); });
    }
}
