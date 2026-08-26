package io.lumnik.hub.connector.api.helpers;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import java.util.Map;
import java.util.Optional;

/** Null/non-string-safe readers for the connector config map (one home for the recurring guards). */
public final class Configs {
    private Configs() {}

    /** A present, non-blank String value, else empty (null / non-String / blank → empty). */
    public static Optional<String> string(Map<String, Object> config, String key) {
        Object v = config == null ? null : config.get(key);
        return (v instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    /** Require a present, non-blank String — same message as the old per-type requireString. */
    public static String requireString(Map<String, Object> config, String key) {
        return string(config, key).orElseThrow(() ->
            new ConfigValidationException("Missing or blank config key: '" + key + "'"));
    }
}
