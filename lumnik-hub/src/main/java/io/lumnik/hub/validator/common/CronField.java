package io.lumnik.hub.validator.common;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;

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
}
