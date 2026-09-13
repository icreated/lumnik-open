package io.lumnik.hub.testsupport;

import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationResult;

import java.util.List;

/**
 * The one place a test asks "which keys did the closed sweep name?".
 *
 * <p>Four validator tests (jdbc, csv, rest, and closed-edition kafka) rest every closed-ConfigDef
 * assertion on this predicate, and it is coupled by hand to a production string —
 * {@code ConfigDefRunner}'s {@code "unknown key — ignored by the runtime"}. One copy, so that
 * coupling has one place to break. It lives outside {@code pro/**} deliberately: the kafka caller
 * is pruned from the open edition, this is not.
 */
public final class ValidationWarnings {

    private ValidationWarnings() {}

    /**
     * The unknown-key family only, sorted. Two reasons for each half: the scopes advisory is a
     * warning that is not a defect and must never colour these assertions, and a sorted list keeps
     * an expectation from depending on the order the YAML happened to be written in.
     */
    public static List<String> unknownKeyPaths(ValidationResult r) {
        return r.warnings().stream()
            .filter(w -> w.message().startsWith("unknown key"))
            .map(ValidationError::path)
            .sorted()
            .toList();
    }
}
