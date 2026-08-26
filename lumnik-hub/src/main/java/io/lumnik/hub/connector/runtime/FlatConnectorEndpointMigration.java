package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-time bootstrap: creates synthetic {@code ConnectorEndpoint} rows for every
 * legacy flat connector ({@code jdbc-generic}, {@code csv-file}, {@code hello-world})
 * that has its table config embedded in the connector-level config (i.e. uses the old
 * flat keys such as {@code table-name}).
 *
 * <p>Idempotent: connectors that already have at least one endpoint are skipped.
 *
 * <p>Runs at startup via {@link StartupEvent}, before the scheduler tick, wrapped in
 * {@code TenantContext.runAsSystem} so RLS is bypassed for the cross-tenant scan.
 */
@ApplicationScoped
public class FlatConnectorEndpointMigration {

    private static final String TABLE_NAME = "table-name";
    private static final String CONNECTOR_ID_QUERY = "connectorId = ?1";
    private static final String WATERMARK_COLUMN = "watermark_column";

    private static final Logger LOG = Logger.getLogger(FlatConnectorEndpointMigration.class);

    /** Connector type ids that may have flat config and need migration. */
    private static final Set<String> FLAT_TYPES = Set.of("jdbc-generic", "csv-file", "hello-world");

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;

    /** Triggered at application startup — runs before the scheduler's first tick. */
    void onStart(@Observes StartupEvent ev) {
        TenantContext.runAsSystem(this::migrateAll);
    }

    /**
     * Public entry point so tests can invoke the migration directly without waiting for a
     * StartupEvent. Idempotent.
     */
    public void migrateAll() {
        List<Connector> candidates = connectors.listAll();
        int migrated = 0;
        for (Connector c : candidates) {
            if (!FLAT_TYPES.contains(c.getTypeId())) continue;
            if (!isFlatConfig(c.getConfig())) continue;
            // Each connector in its OWN new transaction (explicit, call-path-independent —
            // not relying on @Transactional self-invocation interception), so a failure on
            // one connector does not roll back or abort the others.
            try {
                migrated += QuarkusTransaction.requiringNew().call(() -> migrateOne(c));
            } catch (Exception e) {
                LOG.errorf(e, "FlatConnectorEndpointMigration: failed to migrate connector %s — skipping: %s",
                    c.getId(), e.getMessage());
            }
        }
        if (migrated > 0) {
            LOG.infof("FlatConnectorEndpointMigration: migrated %d connector(s) to synthetic endpoints", migrated);
        }
    }

    /**
     * Migrates a single connector. Always invoked inside an explicit new transaction
     * (see {@link #migrateAll()}), which is what gives per-connector failure isolation.
     *
     * @return 1 if a synthetic endpoint was created, 0 if the connector was skipped.
     */
    int migrateOne(Connector c) {
        // The batch startup migration only acts on table-bearing (jdbc) flat connectors —
        // a csv/hello connector with no table-name needs no migration at boot (it gets a
        // synthetic endpoint lazily, on first run, via EndpointResolver). Keep that filter
        // here; ensureSyntheticEndpoint itself handles the table-less case for the lazy path.
        Map<String, Object> flatConfig = c.getConfig();
        String tableName = flatConfig != null ? (String) flatConfig.get(TABLE_NAME) : null;
        if (tableName == null || tableName.isBlank()) {
            return 0;
        }

        // Idempotency guard: skip if any endpoint already exists.
        if (!endpoints.list(CONNECTOR_ID_QUERY, c.getId()).isEmpty()) return 0;

        ensureSyntheticEndpoint(c);   // creates the endpoint + backfills cursor/run rows
        return 1;
    }

    /**
     * Returns the connector's (single) endpoint, creating ONE synthetic endpoint if none exists.
     *
     * <p>Handles BOTH shapes:
     * <ul>
     *   <li><b>table-bearing</b> (jdbc-generic): name = {@code table-name}, config = unified
     *       keys translated by {@link #buildEndpointConfig};</li>
     *   <li><b>table-less</b> (hello-world {@code {count:N}}, csv-file): name = the connector's
     *       name (or {@code "default"}), config = a copy of the connector's flat config as-is
     *       (so e.g. {@code count} / {@code path} is carried verbatim).</li>
     * </ul>
     *
     * <p>Idempotent: if any endpoint already exists for the connector, the first one is returned
     * unchanged. NOT {@code @Transactional} — the caller owns the transaction
     * ({@code migrateOne} runs inside {@code QuarkusTransaction.requiringNew}; the lazy path in
     * {@code EndpointResolver} wraps it in its own {@code requiringNew}).
     */
    public ConnectorEndpoint ensureSyntheticEndpoint(Connector c) {
        UUID connectorId = c.getId();

        List<ConnectorEndpoint> existing = endpoints.list(CONNECTOR_ID_QUERY, connectorId);
        if (!existing.isEmpty()) return existing.get(0);

        Map<String, Object> flatConfig = c.getConfig() != null ? c.getConfig() : Map.of();
        String tableName = (String) flatConfig.get(TABLE_NAME);

        String name;
        Map<String, Object> epConfig;
        if (tableName != null && !tableName.isBlank()) {
            name = tableName;
            epConfig = buildEndpointConfig(flatConfig);
        } else {
            name = (c.getName() != null && !c.getName().isBlank()) ? c.getName() : "default";
            epConfig = new HashMap<>(flatConfig);   // carry count / path / etc. as-is
        }

        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setTenantId(c.getTenantId());
        ep.setConnectorId(connectorId);
        ep.setName(name);
        ep.setMethod("GET");
        ep.setPath("/" + name);
        ep.setEnabled(true);
        ep.setConfig(epConfig);
        endpoints.persist(ep);
        endpoints.flush();  // ensures ep.getId() is assigned

        backfillRuns(connectorId, ep.getId());

        LOG.debugf("ensureSyntheticEndpoint: created endpoint '%s' (id=%s) for connector %s",
            name, ep.getId(), connectorId);
        return ep;
    }

    /**
     * Backfill endpoint_id on the connector's existing run rows. (Cursors are now endpoint-keyed
     * — V19 — so there is no legacy connector-keyed cursor to re-point: fresh ingestion creates
     * per-endpoint cursor rows directly.)
     */
    private void backfillRuns(UUID connectorId, UUID endpointId) {
        for (ConnectorRun run : runs.list(CONNECTOR_ID_QUERY, connectorId)) {
            if (run.getEndpointId() == null) {
                run.setEndpointId(endpointId);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Returns true if the connector's config has a {@code table-name} key (flat layout). */
    private boolean isFlatConfig(Map<String, Object> config) {
        return config != null && config.containsKey(TABLE_NAME);
    }

    /**
     * Translates flat connector config keys to unified endpoint config keys.
     * Keeps connection keys (url, user, password, dialect) for completeness; they are
     * harmless on the endpoint and will be overridden by the connector-level connection
     * config in Lot 3a.
     */
    private Map<String, Object> buildEndpointConfig(Map<String, Object> flat) {
        Map<String, Object> ep = new HashMap<>(flat);

        // Translate table keys.
        rename(ep, "table-schema", "schema");
        rename(ep, TABLE_NAME, "name");
        rename(ep, "pk-columns", "pk_columns");
        rename(ep, "watermark-column", WATERMARK_COLUMN);
        rename(ep, "chunk-size", "chunk_size");
        // "mode" stays as-is.

        // Remove the old hyphenated keys if they were not already overwritten.
        ep.remove("table-schema");
        ep.remove(TABLE_NAME);
        ep.remove("pk-columns");
        ep.remove("watermark-column");
        ep.remove("chunk-size");

        // Remove watermark_column if it maps to null/absent.
        if (ep.get(WATERMARK_COLUMN) == null) {
            ep.remove(WATERMARK_COLUMN);
        }

        return ep;
    }

    /** Copies a value from {@code oldKey} to {@code newKey} if present. */
    private void rename(Map<String, Object> map, String oldKey, String newKey) {
        Object val = map.get(oldKey);
        if (val != null) {
            map.put(newKey, val);
        }
    }
}
