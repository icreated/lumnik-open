package io.lumnik.hub.validator.common;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class CronField {
    private CronField() {}

    private static final CronParser UNIX_PARSER =
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public static Function<JsonNode, Optional<String>> validCron() {
        return v -> {
            String expr = v.asText();
            if (expr == null || expr.isBlank()) {
                return Optional.of("cron expression must not be blank");
            }
            try {
                UNIX_PARSER.parse(expr).validate();
                return Optional.empty();
            } catch (Exception e) {
                return Optional.of("invalid cron expression: " + expr);
            }
        };
    }

    /**
     * Adds the refusal when {@code schedule} is present, a string, and not a cron.
     *
     * <p>{@code validCron} stays a predicate about a cron STRING — asked about the number 22 it
     * would have to answer something, and both answers are lies. So the shape is filtered here
     * instead: every connector declares {@code schedule} as {@code Type.STRING}, so a non-string
     * is already refused by the declaration, and running the cron parser on the coerced
     * {@code asText()} would report one mistake twice. YAML makes that easy to hit — an unquoted
     * {@code schedule: 0 6 * * *} is not a string at all.
     *
     * <p>The {@code example} is a parameter because the four connectors teach different cadences
     * on purpose: every 15 minutes for a jdbc dump, a 5-minute catch-up for kafka.
     */
    public static void check(JsonNode cron, List<ValidationError> errs, String example) {
        if (cron.isMissingNode() || cron.isNull() || !cron.isTextual()) return;
        validCron().apply(cron).ifPresent(msg ->
            errs.add(new ValidationError("spec.schedule", Severity.ERROR, msg, List.of(example))));
    }
}
