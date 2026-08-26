package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.runtime.RunWindow;

import java.util.Optional;
import java.util.function.Function;

/** CronField's twin for spec.window — delegates to RunWindow, the single truth (P10). */
public final class WindowField {
    private WindowField() {}

    public static Function<JsonNode, Optional<String>> validWindow() {
        return v -> RunWindow.validate(v.asText());
    }
}
