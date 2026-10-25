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
        // It also carries NO `target` block, deliberately: re-declaring one on this kind fails here
        // twice over — as a required-field error, and as the unknown key it would become (#543).
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r),
            "every key this fixture carries is honored — none may be called unknown");
    }

    /**
     * The other half of the same removal, and the half that was a defect rather than noise. A jdbc
     * manifest declaring {@code target.indexes} was accepted and then dropped in silence: jdbc reads
     * its indexes PER TABLE ({@code source.tables[].indexes}, via
     * {@code JdbcConnectorType.parseIndexes} off the endpoint config), never off {@code spec.target}.
     * Dropping the declaration hands the refusal to the closed sweep, which already owns the right
     * sentence — so the integrator is told instead of reassured, and no new code says it.
     */
    // ── #552: a key written with nothing after it, at the per-table cross-field rules ─────

    private static final String JDBC_HEAD = """
        {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
         "metadata":{"name":"empty-key","connector_type":"jdbc-generic","scopes":["test"]},
         "spec":{"connection":{"url":"jdbc:postgresql://db.acme.io:5432/billing",
                               "user":"ro_user","password_env":"ACME_DB_PASSWORD"},
                 "defaults":{"schema":"public"},
                 "source":{"tables":[%s]}}}""";

    /**
     * A pin on behaviour that is already right, and on the distance that makes it right (#552).
     * {@code pk_columns:} written empty is a NullNode: not missing, and not an array either, so
     * this rule's own predicate answers false on BOTH terms and would never fire. It fires because
     * the node never reaches it in that shape — {@code TableDefaultsMerger} drops null-valued fields
     * (#551), so the merged table this rule reads has no {@code pk_columns} at all.
     *
     * <p>Which is why it is pinned HERE rather than at the merger: the guarantee lives one class
     * away from the rule that depends on it, and nothing else would say so if it moved.
     */
    @Test void pkColumnsWrittenEmpty_isRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"invoices\",\"mode\":\"full\",\"pk_columns\":null}")));

        assertFalse(r.isValid(), () -> "pk_columns written empty names no key");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".pk_columns")),
            () -> "the refusal must name pk_columns, got: " + r.errors());
    }

    /** The same question one rule over, safe by the same distance: mode=watermark, column empty. */
    @Test void watermarkColumnWrittenEmpty_isRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"invoices\",\"mode\":\"watermark\",\"watermark_column\":null,"
            + "\"pk_columns\":[\"id\"]}")));

        assertFalse(r.isValid(), () -> "watermark mode with no column to watermark on");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".watermark_column")),
            () -> "the refusal must name watermark_column, got: " + r.errors());
    }

    /**
     * And the other direction of the same misreading, also already right: {@code chunk_size} is a
     * dead knob this validator refuses loudly (#286) so no manifest claims a lever that does not
     * exist — but written empty it claims nothing, and the merger having dropped it, nothing is
     * what gets refused.
     */
    @Test void chunkSizeWrittenEmpty_isNotRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"invoices\",\"mode\":\"full\",\"pk_columns\":[\"id\"],"
            + "\"chunk_size\":null}")));

        assertTrue(r.isValid(), () -> "a knob claiming nothing must not be refused, got: " + r.errors());
    }

    /**
     * #1437: jdbc-generic writes rows 1:1 (dumpSpec: rename only), so a `mapping:` block — an operator's Kotlin check,
     * say — never runs. It used to pass with an "unknown key" warning; a control the integrator declared is refused.
     */
    @Test void mappingBlock_isRefused() throws Exception {
        String manifest = JDBC_HEAD.formatted("{\"name\":\"supplier_bank\",\"mode\":\"full\",\"pk_columns\":[\"code\"]}")
            .replace("\"source\":", "\"mapping\":{\"cell\":[{\"kotlin\":{\"script\":\"hooks:iban-check.kts\"}}]},\"source\":");
        ValidationResult r = service.validate(mapper.readTree(manifest));

        assertFalse(r.isValid(), "a mapping block on jdbc-generic must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping") && String.join(" ", e.recommendations()).contains("source.tables[].rename")),
            () -> "the refusal names spec.mapping and says what to do, got: " + r.errors());
        assertTrue(ValidationWarnings.unknownKeyPaths(r).isEmpty(),
            () -> "refused once, not also warned as unknown, got: " + r.warnings());
    }

    @Test void deadTargetBlock_isNamed() throws Exception {
        ValidationResult r = service.validate(load("jdbc-dead-target.yaml"));
        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.target"), ValidationWarnings.unknownKeyPaths(r),
            () -> "the block jdbc never reads must be named, got warnings: " + r.warnings());
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
     * ({@code connection}, {@code source}), so a typo inside one is named while
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

    // ── #1199: target names the hub table; two tables of one connector cannot land in one ──

    @Test void target_thatIsNotAPlainIdentifier_isRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"customers\",\"mode\":\"full\",\"pk_columns\":[\"id\"],\"target\":\"customers-south\"}")));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[0].target")),
            () -> "the refusal must name target, got: " + r.errors());
    }

    @Test void target_thatIsAPlainIdentifier_isAccepted() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"customers\",\"mode\":\"full\",\"pk_columns\":[\"id\"],\"target\":\"customers_south\"}")));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r));
    }

    @Test void twoTablesLandingInOneHubTable_isRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(JDBC_HEAD.formatted(
            "{\"name\":\"customers\",\"mode\":\"full\",\"pk_columns\":[\"id\"],\"target\":\"clients\"},"
            + "{\"name\":\"clients\",\"mode\":\"full\",\"pk_columns\":[\"id\"]}")));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.source.tables[1].name")
                && e.message().contains("connector.t_clients")),
            () -> "the second table landing in connector.t_clients must be refused, got: " + r.errors());
    }

    @Test void duplicateTableNames_isError() throws Exception {
        ValidationResult r = service.validate(load("jdbc-duplicate-tables.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("duplicate table name")));
        assertEquals(1, r.errors().stream().filter(e -> e.path().endsWith(".name")).count(),
            () -> "one mistake, one error — not a second one for the hub table (#1199): " + r.errors());
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
