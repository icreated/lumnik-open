package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.transport.LocalTransportRules;

import java.util.Optional;
import java.util.function.Function;

/**
 * What the flat connector and endpoint doors refuse in a config before storing it: the same rules for both, each
 * door naming only where the run reads its transport — a door cannot take one rule and forget the other. They store a
 * config the manifest's validator never sees; each rule here is one the run or the manifest door applies too: a
 * rename into a hub system column ({@link MapColumnsRules}), a local path whose form the run refuses
 * ({@link LocalTransportRules}, #2001), a hybrid target outside {@code ext} and {@code connector} (#1989). First
 * rule wins. A refusal reads {@code config '<key>' <reason>}, a local
 * path's followed by the gesture that fixes it.
 */
final class StoredConfigRules {

    private StoredConfigRules() {}

    /**
     * A connector's config: its transport where {@link LocalTransportRules#ofConnector} reads it (the connection block
     * or the flat config), and a hybrid target in either place, since a synthetic endpoint copies the flat config whole.
     */
    static Optional<String> connectorRefusal(JsonNode config) {
        // A target reaches the run from either place: the connection block is laid under every endpoint's config
        // (EffectiveConfig.connectionOf), and a synthetic endpoint copies the flat config whole
        // (FlatConnectorEndpointMigration). So both are judged.
        return refusal(config, LocalTransportRules::ofConnector)
            .or(() -> hybridTargetRefusal(config == null ? null : config.path("connection"), "connection.target.table"))
            .or(() -> hybridTargetRefusal(config, "target.table"));
    }

    /** An endpoint's config, laid over its connector's at run time: its own top-level transport. */
    static Optional<String> endpointRefusal(JsonNode config) {
        return refusal(config, LocalTransportRules::ofEndpoint).or(() -> hybridTargetRefusal(config, "target.table"));
    }

    /**
     * A hybrid target the apply door would refuse is not stored by this one either (#1989): a target object naming a
     * table, whatever the table holds (null or a number included). Another {@code target} is not one: jdbc's is the
     * table name as a string, a csv-file's carries its indexes and no table.
     */
    private static Optional<String> hybridTargetRefusal(JsonNode config, String path) {
        JsonNode target = config == null ? null : config.path("target");
        if (target == null || !target.isObject() || !target.has("table")) return Optional.empty();
        JsonNode table = target.get("table");
        return HybridJsonbSpec.targetProblem(table.isTextual() ? table.asText() : null)
            .map(p -> "config '" + path + "' " + p);
    }

    private static Optional<String> refusal(JsonNode config,
                                            Function<JsonNode, Optional<LocalTransportRules.Violation>> transport) {
        if (config == null || config.isNull()) return Optional.empty();   // a save without a config stores none
        return MapColumnsRules.firstStoredConfigViolation(config).map(v -> "config '" + v.path() + "' " + v.reason())
            .or(() -> transport.apply(config).map(v -> "config '" + v.path() + "' " + v.reason() + ". " + v.hint()));
    }
}
