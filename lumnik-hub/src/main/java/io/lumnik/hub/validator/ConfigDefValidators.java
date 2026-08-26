package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ConfigDefValidators {

    private ConfigDefValidators() {}

    public static Function<JsonNode, Optional<String>> isUrl() {
        return v -> {
            String s = v.asText();
            if (s.isBlank()) return Optional.of("must not be blank");
            try {
                URI u = new URI(s);
                if (u.getScheme() == null || u.getHost() == null) {
                    return Optional.of("must be an absolute URL (scheme + host)");
                }
                return Optional.empty();
            } catch (Exception e) {
                return Optional.of("must be a valid URL: " + e.getMessage());
            }
        };
    }

    public static Function<JsonNode, Optional<String>> matchesRegex(String pattern, String desc) {
        Pattern p = Pattern.compile(pattern);
        return v -> p.matcher(v.asText()).matches()
            ? Optional.empty()
            : Optional.of("must match " + desc);
    }

    public static Function<JsonNode, Optional<String>> inRange(int min, int max) {
        return v -> {
            int n = v.asInt();
            return (n >= min && n <= max)
                ? Optional.empty()
                : Optional.of("must be between " + min + " and " + max);
        };
    }

    public static Function<JsonNode, Optional<String>> nonEmpty() {
        return v -> v.asText().isBlank() ? Optional.of("must not be blank") : Optional.empty();
    }

    /**
     * validator == runtime: {@code RestEndpointWriter.parseHybridSpec} refuses a {@code target.table}
     * that is not a plain identifier or {@code schema.table} pair before handing it to
     * {@code HybridJsonbWriter}'s DDL on the BYPASSRLS admin datasource — mirror that refusal here so
     * {@code lm apply} never accepts a manifest that would only fail mid-run.
     */
    public static Function<JsonNode, Optional<String>> plainQualifiedIdentifier() {
        return v -> SqlIdentifiers.isPlainQualifiedIdentifier(v.asText())
            ? Optional.empty()
            : Optional.of("must be a plain identifier or schema.table pair "
                + "(letters, digits, underscore; not starting with a digit)");
    }
}
