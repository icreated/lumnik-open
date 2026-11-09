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

    /**
     * A whole number in {@code [min, max]}: an integral JSON number that fits a long, or the
     * digits of one as text (ASCII, an optional minus).
     * Never {@code asInt()}: {@code 4294967297} wraps to {@code 1} there, a fraction truncates and text reads as 0
     * (#1809). Safe for a LONG field.
     */
    public static Function<JsonNode, Optional<String>> inRange(long min, long max) {
        String refusal = "must be a whole number between " + min + " and " + max;
        return v -> {
            Long n = wholeNumber(v);
            return n != null && n >= min && n <= max ? Optional.empty() : Optional.of(refusal);
        };
    }

    /** The digits of a whole number, as JSON writes one: ASCII only, no leading plus ({@code Long.valueOf} takes both). */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?[0-9]+");

    private static Long wholeNumber(JsonNode v) {
        if (v.isIntegralNumber()) return v.canConvertToLong() ? v.longValue() : null;
        if (!v.isTextual()) return null;
        String digits = v.asText().strip();
        if (!WHOLE_NUMBER.matcher(digits).matches()) return null;
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;   // more digits than a long holds
        }
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
