package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.rest.auth.AuthConfigException;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Applies declarative request headers from the connector config to every request.
 * Each entry is {@code { name, value }} (static) or {@code { name, value_env }} (resolved through
 * {@code secrets} — in production {@code SecretRefs::lookup}: the tenant's {@code lm secret}
 * registry first, then the hub's environment, exactly like the auth layer's {@code *_env} fields).
 * A {@code value_env} that resolves to nothing, or to a blank, is refused with the header's name
 * and the reference — never the value — instead of sending the request without it: the vendor's
 * 401 would blame the source for a secret the hub never found. Applied BEFORE auth so
 * auth-injected headers always win on a name clash.
 */
public final class RequestHeaders {

    private RequestHeaders() {}

    public static RestRequest apply(RestRequest req, Object headersCfg, UnaryOperator<String> secrets) {
        if (!(headersCfg instanceof List<?> list)) return req;
        RestRequest out = req;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> h) || h.get("name") == null) continue;
            String name = String.valueOf(h.get("name"));
            String value;
            if (h.get("value") != null) {
                value = String.valueOf(h.get("value"));
            } else if (h.get("value_env") != null) {
                String envName = String.valueOf(h.get("value_env"));
                value = secrets.apply(envName);
                if (value == null || value.isBlank())
                    throw new AuthConfigException("Header " + name + " unavailable (env=" + envName + ")");
            } else {
                continue;
            }
            out = out.withHeader(name, value);
        }
        return out;
    }
}
