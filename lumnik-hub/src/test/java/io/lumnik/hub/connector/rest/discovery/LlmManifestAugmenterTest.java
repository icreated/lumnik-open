package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.rest.EndpointsBlockValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            "demo", null, "https://api.demo.io", path,
            JsonStructureInference.PaginationKind.PAGE,
            "$.content", "$.id", List.of(), null);
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
}
