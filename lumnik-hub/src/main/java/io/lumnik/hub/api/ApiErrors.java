package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The one shape a hub JSON error body takes: {@code {"error": "…"}}. Single-sourced so the
 * métier-scope refusal — a FROZEN cross-language contract the PWA, {@code lm} and the OTK oracle
 * branch on — cannot drift by a typo in one of the doors that serve it.
 *
 * The decision and its string live in {@link ScopeGrants} (framework-free); this is just the
 * JAX-RS dress. {@code lumnik-rag} carries its own twin for the same reason.
 */
public final class ApiErrors {

    /** The body key — only for the few bodies carrying more than the message (a hint, an {@code ok}). */
    public static final String KEY = "error";

    private ApiErrors() {}

    /**
     * The uniform refusal: {@code 403 {"error":"scope not granted"}}. The content type is set
     * explicitly — two view resources declare JSON <em>and</em> YAML at the class level, so an
     * unnegotiated refusal could otherwise leave the frozen body in the wrong dress.
     */
    public static Response scopeNotGranted() {
        return Response.status(403).type(MediaType.APPLICATION_JSON)
            .entity(error(ScopeGrants.REFUSAL)).build();
    }

    /** The {@code {"error": message}} body. */
    public static Map<String, Object> error(String message) {
        return Map.of(KEY, message);
    }
}
