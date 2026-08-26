package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.validator.JsonNodes;

import java.util.Map;
import java.util.function.Function;

/**
 * The find-or-create preamble every connector apply handler shares.
 *
 * <p>Open by construction: closed-edition handlers ({@code pro.kafka}) consume it, never the reverse.
 */
public final class ApplyUpsert {
    private ApplyUpsert() {}

    /**
     * Find the tenant's connector by name or create it, write the shared fields and persist when new.
     * The {@code config} function receives the connector so a handler can merge onto the existing config.
     */
    public static Connector connector(ConnectorRepository repo, long tenant, String name, String typeId,
                                      JsonNode meta, JsonNode spec,
                                      Function<Connector, Map<String, Object>> config) {
        var existing = repo.find("tenantId = ?1 and name = ?2", tenant, name).firstResultOptional();
        final Connector conn = existing.orElseGet(() -> {
            var c = new Connector();
            c.setTenantId(tenant);
            c.setName(name);
            c.setTypeId(typeId);
            return c;
        });

        conn.setConfig(config.apply(conn));
        conn.setTags(TagsMerger.merge(meta, spec));
        // A10: the scheduler selects on the COLUMN (WHERE c.schedule IS NOT NULL) — the
        // config copy alone never scheduled anything. Absent cron on re-apply = unschedule.
        conn.setSchedule(JsonNodes.textOrNull(spec.path("schedule")));
        // P10: same column-is-truth contract as schedule — absent window on re-apply = fence lifted.
        conn.setRunWindow(JsonNodes.textOrNull(spec.path("window")));

        if (existing.isEmpty()) {
            repo.persist(conn);
            repo.flush();
        }
        return conn;
    }

    /** Find the connector's endpoint by name or create it, write the shared fields and persist when new. */
    public static void endpoint(ConnectorEndpointRepository repo, Connector conn, long tenant,
                                String epName, String method, String path, Map<String, Object> config) {
        ConnectorEndpoint ep = repo.findByName(conn.getId(), epName).orElseGet(() -> {
            var n = new ConnectorEndpoint();
            n.setConnectorId(conn.getId());
            n.setTenantId(tenant);
            n.setName(epName);
            return n;
        });
        ep.setMethod(method);
        ep.setPath(path);
        ep.setEnabled(true);
        ep.setConfig(config);

        if (ep.getId() == null) {
            repo.persist(ep);
        }
    }
}
