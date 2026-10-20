package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();
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
        // Held for the rest of the transaction, like the door's guard read a few lines earlier —
        // this is the second call, so the address and the row are already ours: it never waits (#585, #1231).
        var existing = repo.byAddressForUpdate(tenant, name);
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

    /**
     * Write an optional block into the connector config, or take it out — and the one place the
     * two traps of that gesture are explained, rather than once per handler (#545, #551).
     *
     * <p><b>Absent is {@link JsonNodes#absent}</b>, which owns why the third case is there. What it
     * cost before: a manifest the validator waved through as "block omitted" reached a guard asking
     * {@code isMissingNode()} alone and stored {@code parser: null} — a block the integrator never
     * wrote, printed back by {@code lm connector get}.
     *
     * <p><b>Removing, not skipping.</b> Every {@code buildConnectorConfig} seeds its map from the
     * connector's stored config, so a guard that only ever puts keeps yesterday's block forever and
     * deleting a block from a manifest is a silent no-op. Omission has to MEAN something — that is
     * the whole point of having made these blocks omittable.
     *
     * <p><b>Not for string fields.</b> Theirs is {@link JsonNodes#blankOrNull}, whose javadoc says
     * why the two are not interchangeable in either direction.
     *
     * @param as the shape a present node converts to, {@code Map.class} or {@code List.class} —
     *           explicit rather than {@code Object.class}, so a shape the declaration failed to
     *           refuse still fails here instead of being stored.
     */
    public static void putOrRemove(Map<String, Object> cfg, String key, JsonNode node, Class<?> as) {
        if (JsonNodes.absent(node)) {
            cfg.remove(key);
        } else {
            cfg.put(key, JSON.convertValue(node, as));
        }
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
