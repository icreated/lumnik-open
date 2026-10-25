package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The scope has one home: {@code metadata.scopes}.
 *
 * <p>It is a property of the connector — the scope boundary a bound user must hold to read what
 * it ingests ({@link ScopeGrants}) — not of where its rows land. Before this it lived in two
 * places that merged, {@code metadata.tags} and {@code spec.target.tags}, and the second never
 * reached the merge for REST at all: its {@code target} is per endpoint, the merge read the root.
 * kind:Entity already spells the same concept {@code scopes} and validates it with the same
 * rule; this brings kind:Connector in line rather than inventing a third word.
 *
 * <p>Two families. {@link #errors} is structural and short-circuits like the envelope: the old
 * homes are refused (a WARNING would keep the dual path alive — the refusal names the new home
 * and carries the line to paste), and a scope must be a simple identifier. {@link #warnings}
 * is the one advisory that replaced three per-kind copies: no scopes means not askable.
 */
public final class ScopesValidator {

    public static final String SCOPES = "metadata.scopes";

    private ScopesValidator() {}

    public static List<ValidationError> errors(JsonNode manifest) {
        List<ValidationError> errs = new ArrayList<>();
        JsonNode meta = manifest.path("metadata");
        JsonNode spec = manifest.path("spec");

        // Migration refusals: the old homes. Remove once no manifest in the wild says `tags`
        // (the REST endpoint shape below is the only kind-specific line in this pre-kind stage).
        moved(meta.path("tags"), "metadata.tags", errs);
        moved(spec.path("target").path("tags"), "spec.target.tags", errs);
        JsonNode endpoints = spec.path("endpoints");
        if (endpoints.isArray()) {
            for (int i = 0; i < endpoints.size(); i++) {
                moved(endpoints.get(i).path("target").path("tags"),
                    "spec.endpoints[" + i + "].target.tags", errs);
            }
        }

        JsonNode scopes = meta.path("scopes");
        if (scopes.isMissingNode() || scopes.isNull()) return errs;
        if (!scopes.isArray()) {
            errs.add(new ValidationError(SCOPES, Severity.ERROR,
                "scopes must be a list", List.of("Write: scopes: [compta]")));
            return errs;
        }
        for (int i = 0; i < scopes.size(); i++) {
            JsonNode s = scopes.get(i);
            String problem = s.isTextual()
                ? ScopeGrants.nameProblem(s.asText()).orElse(null)
                : "scope must be a string, got " + s;
            if (problem != null) {
                errs.add(new ValidationError(SCOPES + "[" + i + "]", Severity.ERROR, problem,
                    List.of("The role is spelled scope:<name>; the scope itself is just <name>")));
            }
        }
        return errs;
    }

    public static List<ValidationError> warnings(JsonNode manifest) {
        JsonNode scopes = manifest.path("metadata").path("scopes");
        if (scopes.isArray() && scopes.size() > 0) return List.of();
        return List.of(new ValidationError(SCOPES, Severity.WARNING,
            "no scopes declared — a user bound to a scope will not see this connector, its runs or its dead letters",
            List.of("Add: scopes: [compta]  (a user must hold the role scope:compta)")));
    }

    /**
     * The refusal carries the value it found, as the line to paste at the new home — always a
     * list, whatever shape the old field had: {@code tags: null} once rendered as
     * {@code scopes: null}, which this same validator refuses one step later.
     */
    private static void moved(JsonNode old, String path, List<ValidationError> errs) {
        if (old.isMissingNode()) return;
        StringJoiner value = new StringJoiner(", ", "[", "]");
        if (old.isArray()) old.forEach(t -> value.add(t.asText()));
        else if (old.isTextual() && !old.asText().isBlank()) value.add(old.asText());
        errs.add(new ValidationError(path, Severity.ERROR,
            path + " moved to metadata.scopes — the scope is a property of the connector, "
                + "not of where its rows land",
            List.of("Write under metadata: scopes: " + value)));
    }
}
