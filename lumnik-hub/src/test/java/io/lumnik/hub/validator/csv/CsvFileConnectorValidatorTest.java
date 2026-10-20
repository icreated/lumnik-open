package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConnectorValidationService;
import io.lumnik.hub.testsupport.ValidationWarnings;
import io.lumnik.hub.validator.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CsvFileConnectorValidatorTest {

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper mapper;

    private JsonNode load(String name) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/validator/" + name)) {
            Object loaded = new Yaml().load(is);
            return mapper.valueToTree(loaded);
        }
    }

    @Test void localManifest_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-local-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /**
     * Both blocks a csv manifest could only ever write to say nothing. One assertion covers both
     * because the refusal names its own path: a regression on either comes back as
     * {@code spec.parser} or {@code spec.target}, not as an anonymous red (#543).
     */
    @Test void localManifest_withoutParserOrTarget_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-no-optional-blocks.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1195: one local file, with the removal guard declared — in each accepted form. */
    @ParameterizedTest
    @ValueSource(strings = {"30%", "100%", "off", "0%"})
    void refresh_onOneLocalFile_isAccepted(String maxRemoved) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec").path("transport")).put("path", "/imports/ref.csv");
        ((ObjectNode) doc.path("spec")).putObject("refresh").put("max_removed", maxRemoved);
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertTrue(r.warnings().stream().noneMatch(w -> w.path().startsWith("spec.refresh")), () -> r.warnings().toString());
    }

    @Test void refresh_withAMalformedMaxRemoved_isRefusedNamingTheForms() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec").path("transport")).put("path", "/imports/ref.csv");
        ((ObjectNode) doc.path("spec")).putObject("refresh").put("max_removed", "150%");
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.refresh.max_removed")
            && e.message().contains("0% to 100%")), () -> r.errors().toString());
    }

    /** #1214 review: an absent kind is the runtime's local; the refresh warning must agree. */
    @Test void refresh_withoutATransportKind_isNotTakenForADropFolder() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ObjectNode tr = (ObjectNode) doc.path("spec").path("transport");
        tr.remove("kind");
        tr.put("path", "/imports/ref.csv");
        ((ObjectNode) doc.path("spec")).putObject("refresh").put("max_removed", "30%");
        ValidationResult r = service.validate(doc);
        assertTrue(r.warnings().stream().noneMatch(w -> w.path().equals("spec.refresh")), () -> r.warnings().toString());
    }

    @Test void refresh_onADropFolder_warnsThatItDoesNothing() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");   // path: /imports/*.csv
        ((ObjectNode) doc.path("spec")).putObject("refresh").put("max_removed", "30%");
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.path().equals("spec.refresh")
            && w.message().contains("does nothing")), () -> r.warnings().toString());
    }

    @Test void sftpManifest_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-sftp-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    @Test void s3Manifest_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-s3-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    @Test void s3Manifest_missingBucket_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-s3-missing-bucket.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("bucket")),
            () -> "expected a bucket error, got: " + r.errors());
    }

    @Test void localManifest_nullPath_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-local-null-path.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("path")),
            () -> "expected a path error, got: " + r.errors());
    }

    @Test void localManifest_missingPath_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-local-no-path.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("path")),
            () -> "expected a path error, got: " + r.errors());
    }

    @Test void sftp_missingBothKeyAndPwd_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-sftp-no-key.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.transport")
            && e.message().contains("key_env")));
    }

    @Test void afterProcessMove_withoutArchive_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-move-no-archive.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.archive_path")));
    }

    private record NoArchive(String name, String archiveLine) {
        @Override public String toString() { return name; }
    }

    /** The three ways a manifest can fail to give a path. YAML spells "nothing" more than once. */
    static Stream<Arguments> spellingsOfNoArchivePath() {
        return Stream.of(
            new NoArchive("the key is absent", ""),
            new NoArchive("the key is there with nothing after it", ",\"archive_path\":null"),
            new NoArchive("the key is there and blank", ",\"archive_path\":\"   \"")
        ).map(Arguments::of);
    }

    /**
     * {@code after_process: move} with no path to move to. Only the first row was refused: the rule
     * asked {@code isMissingNode()}, and a key written with nothing after it is a NullNode — present
     * by that question, absent by every other. The manifest validated green and the apply then
     * stored {@code archive_path} as the literal string {@code "null"} ({@code NullNode.asText()}),
     * so processed files were moved into a directory named "null". The tree already carried the
     * remedy and named this exact trap: {@code JsonNodes.blankOrNull}.
     *
     * <p>s3 rather than the local transport of {@code csv-move-no-archive.yaml}: local refuses
     * {@code move} on its own ({@code localCannotRelocate}), so that fixture raises two errors at
     * once and could not tell which rule this row is testing.
     */
    @ParameterizedTest(name = "after_process: move — {0}")
    @MethodSource("spellingsOfNoArchivePath")
    void moveWithoutAnArchivePath_isRefused(NoArchive c) throws Exception {
        JsonNode manifest = mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"bad-move","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"s3","bucket":"acme-exports"},
                     "after_process":"move"%s}}""".formatted(c.archiveLine()));

        ValidationResult r = service.validate(manifest);

        assertFalse(r.isValid(), () -> "a move with nowhere to move to must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.archive_path")),
            () -> "the refusal must name spec.archive_path, got: " + r.errors());
    }

    // ── #552: what a key written with nothing after it does to a CROSS-FIELD rule ──────────

    /**
     * {@code key_env:} written empty is a NullNode, so {@code !isMissingNode()} calls the credential
     * PRESENT and the rule returns clean. The manifest then applies, and the run reaches for a
     * secret nobody named — at run time, which is the tier this apply-time refusal exists to move
     * the failure off.
     */
    @Test void sftpWithACredentialKeyWrittenEmpty_isRefused() throws Exception {
        JsonNode manifest = mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"empty-key","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"sftp","host":"sftp.acme.io","user":"lumnik",
                                  "path":"/export/*.csv","key_env":null,"strict_host_key":false},
                     "after_process":"none"}}""");

        ValidationResult r = service.validate(manifest);

        assertFalse(r.isValid(), () -> "a credential key written empty names no credential");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.transport")),
            () -> "the refusal must name spec.transport, got: " + r.errors());
    }

    /**
     * strict_host_key defaults to true, and the run then refuses a server whose key it has nothing
     * to check against — so a missing fingerprint is refused at validate, not at every run (#1251).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "\"key_env\":\"K\"",
        "\"key_env\":\"K\",\"strict_host_key\":true",
        "\"key_env\":\"K\",\"host_key_fingerprint\":\"  \"",
        "\"key_env\":\"K\",\"host_key_fingerprint\":null"})
    void sftpWithoutAFingerprintToCheck_isRefused(String transportKeys) throws Exception {
        ValidationResult r = service.validate(sftpManifest(transportKeys));

        assertFalse(r.isValid(), "the run would refuse to connect");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.transport.host_key_fingerprint")
            && e.recommendations().stream().anyMatch(s -> s.contains("ssh-keyscan -p 22 sftp.acme.io"))), () -> r.errors().toString());
    }

    @Test void sftpWithStrictHostKeyOff_needsNoFingerprint() throws Exception {
        ValidationResult r = service.validate(sftpManifest("\"key_env\":\"K\",\"strict_host_key\":false"));
        assertTrue(r.isValid(), () -> r.errors().toString());
    }

    private JsonNode sftpManifest(String transportKeys) throws Exception {
        return mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"no-fp","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"sftp","host":"sftp.acme.io","user":"lumnik",
                                  "path":"/export/*.csv",%s},
                     "after_process":"none"}}""".formatted(transportKeys));
    }

    /** The twin of the rule above, on the other key of the same either-or. */
    @Test void headerlessWithColumnsWrittenEmpty_isRefused() throws Exception {
        JsonNode manifest = mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"empty-columns","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"local","path":"/export/*.csv"},
                     "parser":{"has_header":false,"columns":null},
                     "after_process":"none"}}""");

        ValidationResult r = service.validate(manifest);

        assertFalse(r.isValid(), () -> "headerless rows still need names");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.parser.columns")),
            () -> "the refusal must name spec.parser.columns, got: " + r.errors());
    }

    /**
     * The other direction of the same misreading: a rule that REFUSES what it sees as written. A
     * one-shot-only key with nothing after it declares nothing, so there is nothing to teach about.
     */
    @Test void aOneShotOnlyKeyWrittenEmpty_isNotRefused() throws Exception {
        JsonNode manifest = mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"empty-oneshot","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"local","path":"/export/*.csv","pre_process":null},
                     "after_process":"none"}}""");

        ValidationResult r = service.validate(manifest);

        assertTrue(r.isValid(), () -> "a key declaring nothing must not be refused, got: " + r.errors());
    }

    /** The shared indexes block, same question: {@code unique:} written empty declares nothing. */
    @Test void indexUniqueWrittenEmpty_isNotRefused() throws Exception {
        JsonNode manifest = mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"empty-unique","connector_type":"csv-file","scopes":["test"]},
             "spec":{"transport":{"kind":"local","path":"/export/*.csv"},
                     "target":{"indexes":[{"columns":["email"],"unique":null}]},
                     "after_process":"none"}}""");

        ValidationResult r = service.validate(manifest);

        assertTrue(r.isValid(), () -> "a flag declaring nothing must not be refused, got: " + r.errors());
    }

    @Test void invalidCron_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-invalid-cron.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.schedule")));
    }

    @Test void badAfterProcessValue_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-bad-after-process.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.after_process")));
    }

    // The door that did not exist. jdbc had TWO doors on these rules and csv had none, which is
    // backwards: jdbc's rename is a narrow config key the hub assembles, csv's is written by hand.
    // A bad rename was accepted by `lm apply`, scheduled, and killed the run on its first row.
    @Test
    void mapColumns_everyBadShape_isRefusedAtApplyTime() throws Exception {
        ValidationResult r = service.validate(load("csv-map-columns-bad.yaml"));

        var messages = r.errors().stream().map(e -> e.path() + " " + e.message()).toList();
        assertAll(
            () -> assertTrue(messages.stream().anyMatch(m -> m.contains("system column")),
                () -> "renaming INTO a hub system column must be refused, got: " + messages),
            () -> assertTrue(messages.stream().anyMatch(m -> m.contains("more than one source")),
                () -> "two sources onto one target must be refused, got: " + messages),
            () -> assertTrue(messages.stream().anyMatch(m -> m.contains("plain identifier")),
                () -> "a target that is not an identifier must be refused, got: " + messages),
            () -> assertTrue(messages.stream().anyMatch(m -> m.contains("not a rename target")),
                () -> "a colon with nothing after it must be refused, got: " + messages),
            () -> assertTrue(messages.stream().anyMatch(m -> m.contains("must be a list")),
                () -> "a drop that is not a list must be refused, got: " + messages),
            // The point of refusing at APPLY time rather than on the first row: all of them, once.
            // EXACTLY five, not ">= 5": the five anyMatch above already force five distinct
            // messages, so a >= assertion could never fail. An exact count also catches the door
            // reporting one violation twice.
            () -> assertEquals(5, messages.stream().filter(m -> m.contains("map-columns")).count(),
                () -> "apply-time validation accumulates; expected every problem once, got: "
                    + messages));
    }

    // Connector runs are chunked transactions: the aggregator's cross-chunk buffer would be
    // silently dropped at every chunk boundary. Honest refusal at validate beats silent loss.
    @Test void mappingWithAggregatorRowHook_isRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-aggregator.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.path().equals("spec.mapping.row[0].aggregator") && e.message().contains("aggregator")),
            () -> "expected an aggregator refusal at spec.mapping.row[0].aggregator, got: " + r.errors());
    }

    @Test void mappingWithStatelessHooks_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-stateless-row.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    // A split-row fan-out multiplies one source row into N fragments; a validate-row placed after
    // it would drop fragments individually, recording more drops than source rows and corrupting
    // records_out into a negative count (found by running the composition, not by inspection).
    @Test void mappingWithValidateRowAfterSplitRow_isRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-validate-after-split.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.path().equals("spec.mapping.row[1].validate-row")
                    && e.message().contains("split-row")),
            () -> "expected a validate-after-split refusal at spec.mapping.row[1].validate-row, got: "
                + r.errors());
    }

    @Test void mappingWithValidateRowBeforeSplitRow_valid_passes() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-validate-before-split.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    // The rule scans every index after split-row, not just the immediate next one — a hook in
    // between (map-columns here) must not let validate-row slip past the refusal.
    @Test void mappingWithValidateRowAfterSplitRow_nonAdjacent_isRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-validate-after-split-nonadjacent.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.path().equals("spec.mapping.row[2].validate-row")
                    && e.message().contains("split-row")),
            () -> "expected a validate-after-split refusal at spec.mapping.row[2].validate-row "
                + "(non-adjacent, separated by map-columns), got: " + r.errors());
    }

    // C5: parser.columns is REQUIRED when has_header:false — the run used to fail where
    // validate had passed clean.
    @Test void headerlessWithoutColumns_isError() throws Exception {
        ValidationResult r = service.validate(load("csv-headerless-no-columns.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.parser.columns")),
            () -> "expected a columns-required error, got: " + r.errors());
    }

    @Test void headerlessWithNullColumns_isError() throws Exception {
        // NullNode is neither missing nor "empty" — the check must treat it as absent.
        ValidationResult r = service.validate(load("csv-headerless-null-columns.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.parser.columns")),
            () -> "expected a columns-required error for columns: null, got: " + r.errors());
    }

    // C3 (closed blocks first), now at every tier: a typo'd key — in a block or at the root —
    // warns instead of no-oping silently forever.
    @Test void unknownKeys_atEveryTier_warn() throws Exception {
        ValidationResult r = service.validate(load("csv-unknown-keys.yaml"));
        assertTrue(r.isValid(), () -> "unknown-key warnings must not fail apply: " + r.errors());
        // One fixture, both tiers: two typos inside closed blocks, and two at the ROOT — the tier
        // that was unswept until `mapping` and `window` were declared. Declaring them was the
        // price of closing: accusing either would have been a warning that lies.
        assertEquals(
            List.of("spec.after_proces", "spec.parser.empy_check", "spec.target.tabel_prefix", "spec.windo"),
            ValidationWarnings.unknownKeyPaths(r),
            () -> "every typo, and nothing else, must be named: " + r.warnings());
    }

    /** The witness that `mapping` is declared rather than accused — shipped manifests carry it. */
    @Test void mappingManifest_carriesNoUnknownKey() throws Exception {
        ValidationResult r = service.validate(load("csv-mapping-stateless-row.yaml"));
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r),
            () -> "mapping is load-bearing (MappingLoader reads it): " + r.warnings());
    }

    // Dead knobs (tribunal B1/B2): parser.chunk_size and target.table_prefix are parsed by
    // nothing at runtime — the manifest stays valid, but each earns an honest WARNING.
    @Test void deadKnobs_warnButStayValid() throws Exception {
        ValidationResult r = service.validate(load("csv-dead-knobs.yaml"));
        assertTrue(r.isValid(), () -> "warnings must not fail apply, got: " + r.errors());
        assertTrue(r.warnings().stream().anyMatch(w ->
                w.path().equals("spec.parser.chunk_size") && w.message().contains("ignored")),
            () -> "expected a chunk_size warning, got: " + r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w ->
                w.path().equals("spec.target.table_prefix") && w.message().contains("ignored")),
            () -> "expected a table_prefix warning, got: " + r.warnings());
    }

    // The local scheduled reader has no per-file completion tracking: archive/delete/move never
    // execute there (files stay in place). Honest refusal beats a silently ignored knob.
    // Remote transports honor it — csv-sftp-valid.yaml carries after_process: archive and passes.
    @Test void localAfterProcessRelocation_isRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-local-after-process-archive.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
                e.path().equals("spec.after_process") && e.message().contains("local")),
            () -> "expected a local after_process refusal, got: " + r.errors());
    }

    // Transport hooks and max_file_size run only on one-shot lm csv ingest; a connector
    // manifest carrying them would be silently ignored on every scheduled run.
    @Test void transportHooksInConnectorManifest_areRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-transport-hooks.yaml"));
        assertFalse(r.isValid());
        for (String key : new String[] {"file_filter", "pre_process", "max_file_size"}) {
            assertTrue(r.errors().stream().anyMatch(e ->
                    e.path().equals("spec.transport." + key)),
                () -> "expected a refusal at spec.transport." + key + ", got: " + r.errors());
        }
    }
}
