package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.validator.JsonNodes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * The find-or-create preamble every connector apply handler shares.
 *
 * <p>Open by construction: closed-edition handlers ({@code pro.kafka}) consume it, never the reverse.
 */
public final class ApplyUpsert {
    private ApplyUpsert() {}

    private static final String SCHEDULE = "schedule";
    private static final String WINDOW = "window";

    /**
     * The spec keys THIS class reads, for every connector type — declared here because the reader
     * is the only honest source for "what the door consumes". Every connector's {@code ConfigDef}
     * must declare them, or its closed key set warns "ignored by the runtime" about a key that
     * lands in the columns the scheduler selects and fences on: removing it on that advice would
     * unschedule a connector or lift a declared fence. Twice now the audit for a closed root was
     * taken from a connector's own package and missed {@code window} (kafka #472, then jdbc/csv/rest);
     * {@code PreambleKeysAreDeclaredTest} is what makes a third time fail here instead of in a
     * manifest.
     */
    public static final Set<String> PREAMBLE_KEYS = Set.of(SCHEDULE, WINDOW);

    /**
     * Find the tenant's connector by name or create it, write the shared fields and persist when new.
     * The {@code config} function receives the connector so a handler can merge onto the existing config.
     */
    public static Connector connector(ConnectorRepository repo, long tenant, String name, String typeId,
                                      JsonNode meta, JsonNode spec,
                                      Function<Connector, Map<String, Object>> config) {
        var existing = repo.byAddress(tenant, name);
        final Connector conn = existing.orElseGet(() -> {
            var c = new Connector();
            c.setTenantId(tenant);
            c.setName(name);
            c.setTypeId(typeId);
            return c;
        });

        conn.setConfig(config.apply(conn));
        conn.setTags(scopes(meta));
        // A10: the scheduler selects on the COLUMN (WHERE c.schedule IS NOT NULL) — the
        // config copy alone never scheduled anything. Absent cron on re-apply = unschedule.
        conn.setSchedule(JsonNodes.textOrNull(spec.path(SCHEDULE)));
        // P10: same column-is-truth contract as schedule — absent window on re-apply = fence lifted.
        conn.setRunWindow(JsonNodes.textOrNull(spec.path(WINDOW)));

        if (existing.isEmpty()) {
            repo.persist(conn);
            repo.flush();
        }
        return conn;
    }

    /**
     * {@code metadata.scopes} as a list, whatever the node holds: a non-list reads as no scopes.
     * Two readers, in this order — the apply door's scope gate, BEFORE validation (a bound user is
     * refused a scopeless or foreign manifest without learning anything else), then the handlers,
     * after {@code ScopesValidator} has refused any malformed list. The connector's {@code tags}
     * column keeps its name: the wire and the storage are not the manifest. This
     * replaced a merger that unioned {@code metadata.tags} with {@code spec.target.tags} and, for
     * REST, read a root {@code target} that does not exist — endpoint tags never arrived.
     */
    public static List<String> scopes(JsonNode meta) {
        JsonNode s = meta.path("scopes");
        if (!s.isArray()) return List.of();
        // distinct() on an ordered stream keeps declared order at O(n) — the OTK pushes 10k here.
        return StreamSupport.stream(s.spliterator(), false).map(JsonNode::asText).distinct().toList();
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
