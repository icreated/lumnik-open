package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The walker extracted from the three closed-inventory manifest parsers (View/Process/Workflow):
 * their exhaustive C3 tests already pin every real refusal path, so this is one focused test on
 * the shared machinery itself, not a re-run of those catalogues.
 */
class ManifestKeysTest {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    @Test
    void a_nested_unknown_key_is_reported_with_its_dotted_path() throws Exception {
        JsonNode root = MAPPER.readTree("""
            metadata:
              name: x
              bogus: y
            """);
        Map<String, Set<String>> allowedByPath = new LinkedHashMap<>();
        allowedByPath.put("metadata", Set.of("name"));

        assertEquals(List.of("metadata.bogus"), ManifestKeys.unknownKeys(root, allowedByPath));
    }

    @Test
    void a_nested_key_that_is_declared_allowed_is_not_reported() throws Exception {
        JsonNode root = MAPPER.readTree("""
            metadata:
              name: x
              bogus: y
            """);
        Map<String, Set<String>> allowedByPath = new LinkedHashMap<>();
        allowedByPath.put("metadata", Set.of("name", "bogus"));

        assertEquals(List.of(), ManifestKeys.unknownKeys(root, allowedByPath));
    }

    /**
     * A mistyped node (object where the {@code []} convention expects an array) is
     * {@code parse()}'s error to raise, not this check's — iterating an ObjectNode's
     * field values here would fabricate unknown-key reports out of the wrong shape.
     */
    @Test
    void an_object_where_an_array_is_expected_contributes_nothing() throws Exception {
        JsonNode root = MAPPER.readTree("""
            spec:
              states:
                wrong: {shape: x}
            """);
        Map<String, Set<String>> allowedByPath = new LinkedHashMap<>();
        allowedByPath.put("spec.states[]", Set.of("name"));

        assertEquals(List.of(), ManifestKeys.unknownKeys(root, allowedByPath));
    }

    @Test
    void an_unknown_key_inside_an_array_element_is_reported_with_its_index() throws Exception {
        JsonNode root = MAPPER.readTree("""
            spec:
              states:
                - name: draft
                - name: sent
                  bogus: y
            """);
        Map<String, Set<String>> allowedByPath = new LinkedHashMap<>();
        allowedByPath.put("spec.states[]", Set.of("name"));

        assertEquals(List.of("spec.states[1].bogus"), ManifestKeys.unknownKeys(root, allowedByPath));
    }

    @Test
    void an_absent_node_at_a_declared_path_reports_nothing() throws Exception {
        JsonNode root = MAPPER.readTree("""
            metadata:
              name: x
            """);
        Map<String, Set<String>> allowedByPath = new LinkedHashMap<>();
        allowedByPath.put("spec", Set.of("scope")); // spec is entirely absent from this document

        assertEquals(List.of(), ManifestKeys.unknownKeys(root, allowedByPath));
    }
}
