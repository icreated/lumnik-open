package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConnectorValidationService;
import io.lumnik.hub.testsupport.ValidationWarnings;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
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
        // The anti-lie guard of a closed key set: this fixture carries both scheduler keys, and
        // `window` is read by the SHARED apply preamble (ApplyUpsert) into the column the scheduler
        // fences on. A sweep that called it unknown would advise removing a declared fence.
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r),
            "every key this fixture carries is honored — none may be called unknown");
    }

    /**
     * An inline credential that WINS: {@code
     * JdbcConnectorType.resolvePassword} returns {@code spec.connection.password} before it ever
     * looks at {@code password_env}, so a manifest that reads safe because it carries
     * {@code password_env} is still overridden by a line above it. The precedence was stated only
     * inside the field's own description, which nobody reads while editing YAML, and the page that
     * tells an integrator to commit manifests had to ban the field with a CI grep because the
     * product itself said nothing (#530).
     *
     * <p>A warning, not a refusal: the field is a documented dev/test affordance. It reaches the
     * integrator on the apply path too, since #531. The ticket called it the only inline-credential
     * field across all manifest kinds; it is not — REST's {@code spec.auth.token_value} is the
     * other, witnessed in {@code RestConnectorValidatorTest}, and only jdbc's overrides its twin.
     */
    @Test void inlinePassword_isAdvisedAgainst() throws Exception {
        ValidationResult r = service.validate(load("jdbc-inline-password.yaml"));
        assertTrue(r.isValid(), () -> "a documented dev/test affordance is not refused: " + r.errors());

        List<ValidationError> advisories = r.warnings().stream()
            .filter(w -> w.path().equals("spec.connection.password")).toList();
        assertEquals(1, advisories.size(), () -> "one field, one advisory: " + r.warnings());
        assertTrue(advisories.get(0).message().contains("password_env"),
            () -> "the advisory must name what it overrides: " + advisories.get(0).message());
        assertFalse(advisories.get(0).recommendations().isEmpty(),
            "an advisory an integrator can act on carries the line to write instead");
    }

    /** …and the manifest that does the right thing hears nothing about it. */
    @Test void passwordEnvAlone_drawsNoAdvisory() throws Exception {
        ValidationResult r = service.validate(load("jdbc-multi-table-valid.yaml"));
        assertEquals(List.of(), r.warnings().stream()
                .filter(w -> w.path().startsWith("spec.connection")).toList(),
            () -> "nothing to advise on a password_env-only connection: " + r.warnings());
    }

    /**
     * The root level was the unswept one: jdbc confines most keys to closed blocks
     * ({@code connection}, {@code source}, {@code target}), so a typo inside one is named while
     * `schedul` or `windo` at the root passed in complete silence — accepted, persisted, and shown
     * back by {@code lm connector get}. The manifest stays VALID: an unknown key is a warning.
     */
    @Test void misspelledRootKeys_areNamed() throws Exception {
        ValidationResult r = service.validate(load("jdbc-unknown-root-keys.yaml"));
        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.schedul", "spec.windo"), ValidationWarnings.unknownKeyPaths(r),
            () -> "both root typos must be named, got warnings: " + r.warnings());
    }

    /**
     * Counted, because a duplicate is the only thing counting shows. Declaring a key gives the
     * runner the SHAPE refusal, so the cross-field rule beside it must stop repeating one — the
     * defect a review found in this branch for {@code headers} and, older than the branch, here.
     */
    @Test void windowThatIsNotAString_isRefusedExactlyOnce() throws Exception {
        ValidationResult r = service.validate(load("jdbc-window-not-a-string.yaml"));
        assertFalse(r.isValid());
        assertEquals(1, r.errors().stream().filter(e -> e.path().equals("spec.window")).count(),
            () -> "one defect, one refusal: " + r.errors());
    }

    /**
     * schedule's turn, found by a Copilot round asking this branch's own question about the twin
     * it had spared: the fix folded `window` and left `schedule` reporting a non-string twice, on
     * all four connector types. The guard lives in {@code CronField.check}, so one witness here
     * covers the shared rule.
     */
    @Test void scheduleThatIsNotAString_isRefusedExactlyOnce() throws Exception {
        ValidationResult r = service.validate(load("jdbc-schedule-not-a-string.yaml"));
        assertFalse(r.isValid());
        assertEquals(1, r.errors().stream().filter(e -> e.path().equals("spec.schedule")).count(),
            () -> "one defect, one refusal: " + r.errors());
    }

    @Test void indexesThatIsNotAList_isRefusedExactlyOnce() throws Exception {
        ValidationResult r = service.validate(load("jdbc-indexes-not-a-list.yaml"));
        assertFalse(r.isValid());
        assertEquals(1, r.errors().stream()
                .filter(e -> e.path().equals("spec.source.tables[1].indexes")).count(),
            () -> "one defect, one refusal, and it keeps its hint: " + r.errors());
        assertTrue(r.errors().stream().anyMatch(e -> e.recommendations().stream()
                .anyMatch(h -> h.contains("columns"))),
            () -> "the refusal must still teach the shape: " + r.errors());
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
