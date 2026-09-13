package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ConfigDefValidators {

    private ConfigDefValidators() {}

    /** The rule itself lives in {@link Urls#whyNotAbsolute}, where the runtime can reach it too. */
    public static Function<JsonNode, Optional<String>> isUrl() {
        return v -> Urls.whyNotAbsolute(v.asText());
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

    /**
     * The same rule unqualified: the value becomes ONE identifier, so a {@code schema.table} pair is
     * wrong here — {@code "t_" + name} would compose {@code t_orders.items}.
     *
     * @param becomes what the value is concatenated into, named in the refusal so the reader learns
     *                why the rule exists rather than only that it exists
     */
    public static Function<JsonNode, Optional<String>> plainIdentifier(String becomes) {
        return v -> SqlIdentifiers.isPlainIdentifier(v.asText())
            ? Optional.empty()
            : Optional.of("must be a plain identifier (letters, digits, underscore; not starting "
                + "with a digit) — it becomes " + becomes);
    }
}
