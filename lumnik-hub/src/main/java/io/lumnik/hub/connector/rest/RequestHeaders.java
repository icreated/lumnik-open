package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Applies declarative request headers from the connector config to every request.
 * Each entry is {@code { name, value }} (static) or {@code { name, value_env }} (resolved
 * via {@link #ENV}: OS env first, then JVM system property). This is NOT the auth layer's
 * resolution, which consults the {@code lm secret} registry before either — a header credential
 * must be a real environment variable. An unresolved {@code value_env} drops the header without
 * a word. Applied BEFORE auth so auth-injected headers always win on a name clash.
 */
public final class RequestHeaders {

    private RequestHeaders() {}

    /**
     * Default resolver: OS environment first, then JVM system property (dev/test) — and nothing
     * else. Deliberately NOT {@code SecretRefs}: a header credential does not reach the
     * {@code lm secret} registry, which is what the class javadoc above says to an integrator.
     */
    public static final UnaryOperator<String> ENV = EnvSecrets::lookup;

    public static RestRequest apply(RestRequest req, Object headersCfg, UnaryOperator<String> env) {
        if (!(headersCfg instanceof List<?> list)) return req;
        RestRequest out = req;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> h) || h.get("name") == null) continue;
            String value;
            if (h.get("value") != null) {
                value = String.valueOf(h.get("value"));
            } else if (h.get("value_env") != null) {
                value = env.apply(String.valueOf(h.get("value_env")));
            } else {
                continue;
            }
            if (value != null) out = out.withHeader(String.valueOf(h.get("name")), value);
        }
        return out;
    }
}
