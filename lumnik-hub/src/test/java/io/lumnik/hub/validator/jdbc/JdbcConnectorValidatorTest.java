package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConnectorValidationService;
import io.lumnik.hub.validator.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcConnectorValidatorTest {

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper mapper;

    private JsonNode load(String name) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/validator/" + name)) {
            Object loaded = new Yaml().load(is);
            return mapper.valueToTree(loaded);
        }
    }

    @Test void multiTableManifest_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("jdbc-multi-table-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    @Test void watermarkMode_withoutWatermarkColumn_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-no-watermark-col.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("watermark_column")));
    }

    @Test void emptyPkColumns_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-empty-pk.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("pk_columns")));
    }

    @Test void chunkSizeOutOfRange_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-chunk-out-of-range.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("chunk_size")));
    }

    // chunk_size was a dead knob: validated in [1,10000] but read by no execution code — the
    // engine always reads 500 (ChunkExecutor → type.defaultChunkSize()). Decision #286: refuse
    // it loudly rather than accept-and-ignore, so no manifest can claim a lever that does not exist.
    @Test void chunkSizeInRange_isRefusedAsDeadKnob() throws Exception {
        ValidationResult r = service.validate(load("jdbc-chunk-size-dead-knob.yaml"));
        assertFalse(r.isValid(), "an in-range chunk_size must be refused — the knob is not wired");
        assertTrue(r.errors().stream().anyMatch(e ->
                e.path().contains("chunk_size") && e.message().contains("not a knob")),
            () -> "expected a 'not a knob' refusal on chunk_size, got: " + r.errors());
    }

    @Test void duplicateTableNames_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-duplicate-tables.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("duplicate table name")));
    }

    @Test void unsupportedDialect_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-unsupported-dialect.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.connection.url")
            && e.message().contains("dialect")));
    }

    @Test void rename_blessedCase_noError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-rename-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().contains("rename")));
    }

    @Test void rename_targetIsSystemColumn_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-rename-system-target.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].rename")));
    }

    @Test void rename_targetNotPlainIdentifier_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-rename-bad-identifier.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].rename")));
    }

    @Test void rename_duplicateTarget_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-rename-duplicate-target.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].rename")));
    }

    @Test void rename_notAnObject_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-rename-not-object.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].rename")));
    }

    @Test void rename_nullTarget_isError() throws Exception {
        // A manifest typo — "rename:\n  id:" — parses to a YAML null, not an empty string;
        // it must not be silently accepted as the literal column name "null" (finding 1).
        ValidationResult r = service.validate(load("jdbc-rename-null-target.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].rename")));
    }

    @Test void rename_absent_unchangedBehavior() throws Exception {
        ValidationResult r = service.validate(load("jdbc-multi-table-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().contains("rename")));
    }
}
