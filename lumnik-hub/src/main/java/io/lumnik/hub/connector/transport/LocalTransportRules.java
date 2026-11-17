package io.lumnik.hub.connector.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * What the flat connector and endpoint doors refuse in a local transport before storing it (#2001): a path whose form
 * the run would refuse — absolute, or not a path at all ({@link LocalRoot#formRefusal}). Those doors store a config the
 * manifest's validator never sees. The transport is judged where the run reads it from, which depends on the shape:
 * a connector's config ({@link #ofConnector}) or an endpoint's, laid over it ({@link #ofEndpoint}).
 */
public final class LocalTransportRules {

    /** The refused key, as the stored config names it, the run's words, and the gesture that fixes it. */
    public record Violation(String path, String reason, String hint) {}

    private LocalTransportRules() {}

    /**
     * A connector's config: the run reads its connection block when it carries one, the flat config otherwise
     * ({@code EffectiveConfig.connectionOf}) — never both, so a key the run ignores is not judged.
     */
    public static Optional<Violation> ofConnector(JsonNode config) {
        if (config == null) return Optional.empty();
        JsonNode connection = config.path("connection");
        return connection.isObject()
            ? ofTransport(connection.path("transport"), "connection.transport.path")
            : ofTransport(config.path("transport"), "transport.path");
    }

    /**
     * An endpoint's config, which the run lays over the connector's key by key ({@code EffectiveConfig.of}): its own
     * top-level {@code transport} is the one read; a {@code connection} key in it is inert.
     */
    public static Optional<Violation> ofEndpoint(JsonNode config) {
        return config == null ? Optional.empty() : ofTransport(config.path("transport"), "transport.path");
    }

    /** Whether a transport block is local as the run reads it: an absent kind is local, an explicit null is not. */
    public static boolean isLocal(JsonNode transport) {
        JsonNode kind = transport.get("kind");
        return kind == null || kind.isTextual() && "local".equals(kind.asText());
    }

    /** One transport block, named {@code at} in a refusal: the rule every door shares, the apply validator's too. */
    public static Optional<Violation> ofTransport(JsonNode transport, String at) {
        if (!transport.isObject() || !transport.path("path").isTextual() || !isLocal(transport)) return Optional.empty();
        return LocalRoot.formRefusal(transport.path("path").asText())
            .map(refusal -> new Violation(at, refusal.message(), refusal.hint()));
    }
}
