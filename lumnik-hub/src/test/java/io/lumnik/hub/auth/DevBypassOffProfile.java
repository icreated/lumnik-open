package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * The dev bypass off, with no OIDC provider configured: an unauthenticated request meets the real filters.
 * One shared class on purpose — Quarkus restarts the application whenever the profile CLASS changes between
 * two test classes, so four look-alike nested profiles (each with the same override) cost four restarts.
 * The RAG start-up index is off too: none of these tests needs it, and two of them had it off already.
 */
public final class DevBypassOffProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "lumnik.security.dev-bypass", "false",
            "lumnik.rag.index-on-startup", "false"
        );
    }
}
