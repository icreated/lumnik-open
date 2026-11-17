package io.lumnik.testkit.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The scope inventories' lists, read from {@code scripts/scope-inventory.json} (#2018): the file
 * {@code scripts/check-scope-inventory.py} reads on every pull request, so the Java inventories and the script cannot
 * drift apart. The deciding calls of {@code ScopeGrants} and {@code ScopeAccess} are not in it: both readers derive them
 * from the {@code @ScopeDecision} methods.
 */
public final class ScopeInventory {

    public static final Path FILE = SourceTree.reactorRoot().resolve("scripts/scope-inventory.json");

    private static final JsonNode DATA = read();

    private ScopeInventory() {}

    /**
     * A boundary section's source tree, {@code <reactor>/<module>/src/main/java/<package>}: exactly the tree the script
     * sweeps, never the cwd's first match ({@code SourceTree.of} tries the module under test first).
     */
    public static SourceTree sources(String section) {
        return SourceTree.at(SourceTree.reactorRoot().resolve(value(section, "module").asText())
            .resolve("src/main/java").resolve(value(section, "package").asText()));
    }

    /** A positive integer the file holds, e.g. a minimum: null, 41.9 or 0 is refused, never read as 0 or rounded. */
    public static int positiveInt(String section, String key) {
        JsonNode n = value(section, key);
        if (!n.isInt() || n.intValue() < 1) throw new IllegalStateException(FILE + ": " + section + "." + key
            + " must be a positive integer, not " + n);
        return n.intValue();
    }

    /** One section's string list, e.g. {@code ("hub_scope_boundary", "touching")}. */
    public static List<String> list(String section, String key) {
        return StreamSupport.stream(value(section, key).spliterator(), false).map(JsonNode::asText).toList();
    }

    /** One section's exemptions: what is exempted, and why. */
    public static Map<String, String> exempt(String section) {
        return map(section, "exempt");
    }

    /** One section's string map, e.g. {@code ("doors", "helper_deciders")}: a helper's call, and its file. */
    public static Map<String, String> map(String section, String key) {
        return value(section, key).properties().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().asText()));
    }

    /**
     * One section's value, e.g. {@code ("doors", "min_doors")}. A key the file does not hold is refused,
     * never read as empty or 0: a misspelled minimum would sweep nothing.
     */
    public static JsonNode value(String section, String key) {
        JsonNode n = DATA.path(section).path(key);
        if (n.isMissingNode()) throw new IllegalStateException(FILE + " holds no " + section + "." + key);
        return n;
    }

    private static JsonNode read() {
        try {
            return new ObjectMapper().readTree(FILE.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + FILE + " — the inventories' lists live there", e);
        }
    }
}
