package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Auto-refreshes the ask-to-SQL SchemaCard after a connector run that changed the hub schema.
 * Probe: connector.dynamic_table rows touched since the run started (the writer updates the registry
 * at table creation AND column evolution). The common incremental run costs one SELECT and returns.
 * Failures are logged and swallowed — the run is already terminal; {@code lm source rediscover} remains the
 * manual fallback. Runs on a CDI worker thread, so DB access establishes system mode; the connector
 * lookup opens a real tx (the Agroal tenant interceptor only fires for JTA-enlisted connections),
 * while rediscover holds none across its reads of the source (#938).
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
    @Inject ConnectorRepository connectors;
    @Inject DiscoveryService discovery;

    void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
        try {
            if (!schemaChangedDuring(ev)) return;
            List<String> tags = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew().call(() -> {
                    var c = connectors.findById(ev.connectorId());
                    return c != null ? List.copyOf(c.getTags()) : List.<String>of();
                }));
            for (String tag : tags) {
                LOG.infof("SchemaCardRefresher: schema changed during run %s — rediscovering scope '%s' (tenant %d)",
                    ev.runId(), tag, ev.tenantId());
                TenantContext.callAsSystem(() -> discovery.rediscover(ev.tenantId(), tag));
            }
        } catch (Exception e) {
            LOG.warnf(e, "SchemaCardRefresher failed for run %s — manual `lm source rediscover` remains available",
                ev.runId());
        }
    }

    private boolean schemaChangedDuring(ConnectorRunCompleted ev) {
        Instant since = ev.runStartedAt().minus(CLOCK_SLACK);
        return Jdbc.queryOne(adminDs, "SchemaCardRefresher.schemaChangedDuring",
            // tenant_id is redundant with a UUID connector id, and stays: this runs on the admin
            // datasource, where nothing else scopes a read, and the guard over that datasource
            // asks every query naming a tenanted table to say whose rows it means.
            "SELECT 1 FROM connector.dynamic_table WHERE tenant_id = ? AND connector_id = ? "
            + "AND updated_at >= ? LIMIT 1",
            ps -> { ps.setLong(1, ev.tenantId()); ps.setObject(2, ev.connectorId());
                    ps.setTimestamp(3, Timestamp.from(since)); },
            rs -> true).isPresent();
    }
}
