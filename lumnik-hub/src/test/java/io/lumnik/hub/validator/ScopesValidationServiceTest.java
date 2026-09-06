package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wiring, not the rule: {@link ScopesValidator}'s refusals short-circuit like the envelope's
 * (a moved field is structural — the kind validator never runs on it), and its advice lands in
 * the warnings of an otherwise valid manifest — ONE site, where the three per-kind
 * {@code targetTagsAdvised} used to be.
 */
@QuarkusTest
class ScopesValidationServiceTest {

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper mapper;

    private JsonNode yaml(String text) {
        return mapper.valueToTree(new Yaml().load(text));
    }

    private static final String CSV_SPEC = """
        spec:
          transport: { kind: local, path: /imports/*.csv }
          parser: { has_header: true }
          after_process: none
          target: {}
        """;

    @Test
    void a_moved_field_is_refused_before_the_kind_validator_runs() {
        ValidationResult r = service.validate(yaml("""
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata: { name: x, connector_type: csv-file, tags: [crm] }
            """ + CSV_SPEC));

        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("metadata.tags")), () -> "" + r.errors());
        assertTrue(r.warnings().isEmpty(), "short-circuited: no kind-level advice on a refused manifest");
    }

    @Test
    void a_scopeless_manifest_is_valid_with_one_piece_of_advice() {
        ValidationResult r = service.validate(yaml("""
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata: { name: x, connector_type: csv-file }
            """ + CSV_SPEC));

        assertTrue(r.isValid(), () -> "" + r.errors());
        assertEquals(1, r.warnings().stream().filter(w -> w.path().equals("metadata.scopes")).count());
        assertTrue(r.warnings().stream().noneMatch(w -> w.path().endsWith("target.tags")),
            "the old advice site is gone");
    }
}
