package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.lumnik.hub.json.JsonNodes.absent;
import static io.lumnik.hub.json.JsonNodes.blankOrNull;

/**
 * The shape of a {@code promote} list — {@code [{ name, path, type, transform? }]} — for the two connectors that
 * read one: rest's {@code endpoints[].target.promote} and kafka's {@code spec.promote} (#1777). One rule set, so
 * a promote entry means the same thing wherever it is written.
 */
public final class PromoteEntries {
    private PromoteEntries() {}

    public static final Set<String> TYPES =
        Set.of("text", "bigint", "integer", "decimal", "boolean", "date", "timestamptz", "jsonb");

    /**
     * promote[] element shape (mirrors TargetBlockValidator.validateIndexes): each entry needs a non-blank name +
     * path + a known type, or the DDL / extraction breaks at runtime. Under a mapping ({@code mapped}), an entry
     * without a path is a column the mapping fills (#1617). {@code noPathHint} is what a missing path recommends, in
     * the connector's own terms. {@code reserved} is the columns the table the entries land in already has: a name among
     * them, or a name written twice, would die at the first run on "column specified more than once" (#1781); a path
     * JsonPath cannot compile, on its first write.
     */
    public static void check(JsonNode promote, String p, boolean mapped, List<String> noPathHint,
                             Set<String> reserved, List<ValidationError> errs) {
        if (promote.isMissingNode() || promote.isNull()) return;
        if (!promote.isArray()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote must be a list of { name, path, type } objects", List.of()));
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < promote.size(); i++) {
            checkEntry(promote.get(i), p + "[" + i + "]", mapped, noPathHint, reserved, names, errs);
        }
    }

    private static void checkEntry(JsonNode e, String p, boolean mapped, List<String> noPathHint,
                                   Set<String> reserved, Set<String> names, List<ValidationError> errs) {
        if (!e.isObject()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote entry must be an object with name/path/type", List.of()));
            return;
        }
        for (String f : List.of("name", "type")) {
            if (blankOrNull(e.path(f))) {
                errs.add(new ValidationError(p + "." + f, Severity.ERROR,
                    f + " is required and must be non-blank", List.of()));
            }
        }
        // The run casts name and path to String: a number or a boolean is a refusal here, not a ClassCastException there.
        JsonNode name = e.path("name");
        if (!absent(name) && !name.isTextual()) {
            errs.add(new ValidationError(p + ".name", Severity.ERROR, "name must be a string", List.of()));
        } else if (!blankOrNull(name)) {
            checkName(name.asText(), p + ".name", reserved, names, errs);
        }
        // Absent or null (`path:` left empty), the column is the mapping's to fill, as the run reads it (#1617);
        // written, it must say where to read.
        JsonNode path = e.path("path");
        boolean none = path.isMissingNode() || path.isNull();
        if (none ? !mapped : blankOrNull(path)) {
            errs.add(new ValidationError(p + ".path", Severity.ERROR, "path is required and must be non-blank",
                none ? noPathHint : List.of()));
        } else if (!none && !path.isTextual()) {
            errs.add(new ValidationError(p + ".path", Severity.ERROR, "path must be a string", List.of()));
        } else if (!none) {
            checkJsonPath(path.asText(), p + ".path", errs);
        }
        if (none && mapped && !absent(e.path("transform"))) {
            errs.add(new ValidationError(p + ".transform", Severity.ERROR,
                "transform reads the value at path — a column the mapping fills has none to read",
                List.of("Drop the transform: the mapping writes this column's value")));
        }
        String type = e.path("type").asText();
        if (!type.isBlank() && !TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            errs.add(new ValidationError(p + ".type", Severity.ERROR,
                "unknown promote type '" + type + "' (allowed: " + new TreeSet<>(TYPES) + ")",
                List.of()));
        }
        // validator == runtime, both ways: the writer's `transformRaw != null` guard treats an
        // explicit null as absent — but a blank/non-text value reaches Transform.valueOf and
        // crashes the run, and so does an unknown name.
        JsonNode transformNode = e.path("transform");
        if (!transformNode.isMissingNode() && !transformNode.isNull()) {
            transformRefusal(transformNode).ifPresent(m ->
                errs.add(new ValidationError(p + ".transform", Severity.ERROR, m, List.of())));
        }
    }

    private static void checkJsonPath(String path, String p, List<ValidationError> errs) {
        try {
            JsonPath.compile(path);
        } catch (InvalidPathException bad) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "path is not a JSONPath: " + bad.getMessage(), List.of("Example: path: $.object.temperature")));
        }
    }

    private static void checkName(String name, String p, Set<String> reserved, Set<String> names,
                                  List<ValidationError> errs) {
        if (reserved.contains(name)) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "'" + name + "' is a hub system column (" + DynamicTableWriter.columnList(reserved) + ")",
                List.of("Name the promoted column otherwise")));
        } else if (!names.add(name)) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "column '" + name + "' is promoted twice — a table has one column of that name", List.of()));
        }
    }

    /** Why {@code transform} names no promote transform, if it does not — a promote entry's and the key's (#1630). */
    public static Optional<String> transformRefusal(JsonNode transformNode) {
        String transform = transformNode.isTextual() ? transformNode.asText() : "";
        String allowed = Arrays.stream(HybridJsonbSpec.Transform.values())
            .map(t -> t.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));
        if (transform.isBlank()) return Optional.of("transform must be a non-blank string (allowed: " + allowed + ")");
        try {
            HybridJsonbSpec.Transform.valueOf(transform.toUpperCase(Locale.ROOT));
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.of("unknown transform '" + transform + "' (allowed: " + allowed + ")");
        }
    }
}
