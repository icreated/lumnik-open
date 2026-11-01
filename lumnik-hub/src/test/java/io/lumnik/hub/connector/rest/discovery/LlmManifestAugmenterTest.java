package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.connector.naming.JsonPaths;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.rest.EndpointsBlockValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prober's block is handed to a human to paste into a manifest (`RestDiscoveryResource`),
 * so it has to satisfy the same per-endpoint contract the hub enforces on apply. Asserting on
 * the emitted string would prove nothing — that is exactly how the sibling importer shipped a
 * manifest its own validator rejected.
 */
class LlmManifestAugmenterTest {

    private JsonNode probed(String path) {
        String block = new LlmManifestAugmenter().augment(
            "demo", path, JsonStructureInference.PaginationKind.PAGE, "$.data", 1, "$.id", List.of());
        try {
            return new YAMLMapper().readTree(block);
        } catch (Exception e) {
            throw new AssertionError("the prober emitted YAML the hub cannot parse:\n" + block, e);
        }
    }

    @Test void probed_endpoint_satisfies_the_per_endpoint_config_def() {
        JsonNode seq = probed("/v1/things");
        assertTrue(seq.isArray() && !seq.isEmpty(), "the prober did not emit an endpoint list");
        List<ValidationError> errs =
            ConfigDefRunner.run(EndpointsBlockValidator.perEndpointDef(), seq.get(0), "spec.endpoints[]");
        assertTrue(errs.isEmpty(), () -> "the hub rejects the prober's own endpoint: " + errs);
    }

    /** The integrator reads the first line: it names the gesture and asks for a review, never an engine class. */
    @Test void the_block_opens_with_a_neutral_header() {
        String block = new LlmManifestAugmenter().augment(
            "demo", "/v1/things", JsonStructureInference.PaginationKind.PAGE, "$.data", 1, "$.id", List.of());
        assertEquals("# Drafted by lm source rest probe — review before applying", block.lines().findFirst().orElseThrow());
    }

    /** #1650: the no-pages draft carries its response_path, and passes the hub's own per-endpoint contract. */
    @Test void a_no_pages_draft_satisfies_the_per_endpoint_config_def() throws Exception {
        String block = new LlmManifestAugmenter().augment(
            "demo", "/vets.json", JsonStructureInference.PaginationKind.NONE, "$.vetList", 6, "$.id", List.of());
        JsonNode seq = new YAMLMapper().readTree(block);
        assertEquals("$.vetList", seq.get(0).path("pagination").path("response_path").asText());
        List<ValidationError> errs =
            ConfigDefRunner.run(EndpointsBlockValidator.perEndpointDef(), seq.get(0), "spec.endpoints[]");
        assertTrue(errs.isEmpty(), () -> "the hub rejects the prober's own endpoint: " + errs);
    }

    /**
     * #1652: the paging kinds require response_path, and the probe knew it. A list found is written for
     * whatever kind the envelope gave, not only for none.
     */
    @ParameterizedTest
    @EnumSource(value = JsonStructureInference.PaginationKind.class, names = {"PAGE", "OFFSET", "CURSOR", "RELAY", "LINK_HEADER", "UNKNOWN"})
    void a_found_list_is_written_as_response_path_for_every_kind(JsonStructureInference.PaginationKind kind) throws Exception {
        String block = new LlmManifestAugmenter().augment("demo", "/v1/things", kind, "$.data", 1, "$.id", List.of());
        assertEquals("$.data", new YAMLMapper().readTree(block).get(0).path("pagination").path("response_path").asText(), block);
    }

    /** The document itself is the record: there is no list to point at, so no response_path is invented. */
    @Test void no_list_found_writes_no_response_path() {
        String block = new LlmManifestAugmenter().augment(
            "demo", "/v1/thing", JsonStructureInference.PaginationKind.UNKNOWN, null, 0, "$.id", List.of());
        assertTrue(block.contains("  pagination: { kind: unknown }\n"), block);
    }

    /** A promote path in bracket notation survives the YAML round trip, quote and backslash included. */
    @Test void a_bracket_path_comes_back_from_the_yaml_as_it_was_written() throws Exception {
        List<String> paths = List.of(JsonPaths.member("Modèle d'adaptation"), JsonPaths.member("say \"hi\""), "$.plain_key");
        List<Map<String, Object>> promotes = paths.stream()
            .map(path -> Map.<String, Object>of("name", "c" + path.length(), "path", path, "type", "text")).toList();
        String block = new LlmManifestAugmenter().augment(
            "demo", "/v1/things", JsonStructureInference.PaginationKind.PAGE, "$.data", 1, "$.id", promotes);
        JsonNode endpoint = new YAMLMapper().readTree(block).get(0);
        assertEquals(paths, endpoint.path("target").path("promote").findValuesAsText("path"));
    }
}
