package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.List;

/**
 * A connector type that writes rows as its source holds them refuses a {@code mapping:} block, worded once (#1437).
 * jdbc-generic, kafka and rest-generic have no transformer chain; a {@code mapping:} — an operator's Kotlin check,
 * say — passed with an "unknown key" warning and never ran. Each type declares the key with {@link #DOC}, so the
 * closed-key sweep does not also warn, and calls {@link #refuse} with what to do instead.
 */
public final class NoMappingBlock {
    private NoMappingBlock() {}

    /** The declaration text, for the {@code optional("mapping", …)} line of each type. */
    public static final String DOC = "Not accepted — refused by validation (rows land as the source holds them)";

    private static final String CSV = "Transformers and Kotlin scripts run on a csv-file connector's spec.mapping";

    /**
     * Adds the refusal when {@code mapping} is an object. A non-object is the declaration's type error already;
     * refused here too, one mistake would read twice.
     */
    public static void refuse(String kind, JsonNode mapping, String instead, List<ValidationError> errs) {
        refuse(kind, mapping, "spec.mapping", instead, errs);
    }

    /** The same refusal one tier down, named by its own path — a rest-generic endpoint's {@code mapping} (#1438). */
    public static void refuse(String kind, JsonNode mapping, String path, String instead, List<ValidationError> errs) {
        if (!mapping.isObject()) return;
        errs.add(new ValidationError(path, Severity.ERROR,
            kind + " has no mapping block — rows land as the source holds them, so nothing declared here would run",
            List.of(instead, CSV)));
    }
}
