package io.lumnik.hub.connector.rest;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Applies declarative request headers from the connector config to every request.
 * Each entry is {@code { name, value }} (static) or {@code { name, value_env }} (resolved
 * via {@link #ENV}: OS env first, then JVM system property — matching the auth layer's
 * resolution). Applied BEFORE auth so auth-injected headers always win on a name clash.
 */
public final class RequestHeaders {

    private RequestHeaders() {}

    /** Default secret resolver: OS environment first, then JVM system property (dev/test). */
    public static final UnaryOperator<String> ENV = name -> {
        String v = System.getenv(name);
        return v != null ? v : System.getProperty(name);
    };

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
