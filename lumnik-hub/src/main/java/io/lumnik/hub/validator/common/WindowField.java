package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.runtime.RunWindow;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * CronField's twin for spec.window — delegates to RunWindow, the single truth (P10).
 *
 * <p>It owns the two strings the integrator actually reads, because every connector type declares
 * and checks this key and the copies had already drifted: two of the four hints said "end
 * exclusive, wraps midnight" and two said "scheduled runs are skipped outside it", for one rule.
 * {@code f.doc()} is served over HTTP by {@code ConfigDefSerializer}, so a fourth wording would be
 * a fourth answer to {@code lm describe}.
 */
public final class WindowField {
    private WindowField() {}

    /** The declaration text, for the {@code optional("window", …)} line of every connector. */
    public static final String DOC =
        "Fence scheduled runs to a UTC time window, HH:MM-HH:MM (a manual run bypasses it)";

    private static final String HINT =
        "Example: window: \"22:00-06:00\" (UTC, end exclusive, wraps midnight; a slot due outside "
            + "the window is skipped, never queued)";

    /** Kept private: the four connector types go through {@link #check}, one wording for all. */
    private static Function<JsonNode, Optional<String>> validWindow() {
        return v -> RunWindow.validate(v.asText());
    }

    /**
     * Adds the refusal, worded once, when {@code window} is present and malformed. Absent or
     * explicitly null is no window at all, which is legal — a connector without a fence.
     */
    public static void check(JsonNode window, List<ValidationError> errs) {
        if (window.isMissingNode() || window.isNull()) return;
        // The SHAPE is the declaration's: `optional("window", Type.STRING, …)` already refuses a
        // non-string, and YAML makes that easy to hit — unquoted `window: 22:00` is sexagesimal,
        // so it arrives as the number 1320. Checking it here too would report one mistake twice.
        if (!window.isTextual()) return;
        validWindow().apply(window).ifPresent(msg ->
            errs.add(new ValidationError("spec.window", Severity.ERROR, msg, List.of(HINT))));
    }
}
