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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * #1402: a Kotlin script is resolved and compiled at apply. Before, `lm apply` said ✓ and the run
     * failed on its first row.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "file:/hooks/ean-check.kts|classpath:<resource> or hooks:<name>.kts",
        "classpath:mapping/no-such-script.kts|mapping/no-such-script.kts",
        "classpath:mapping/does-not-compile.kts|does-not-compile.kts:2:14",
        "hooks:ean-check.kts|no hooks directory"})
    void aKotlinScriptThisHubCannotRun_isRefusedAtApply(String script, String said) throws Exception {
        ValidationResult r = service.validate(withKotlinCell("classpath:mapping/uppercase-phone.kts", script));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[1].kotlin")
            && e.message().contains(said)), () -> r.errors().toString());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().startsWith("spec.mapping.cell[0]")), () -> r.errors().toString());
    }

    /** Copilot on #1404: `lm hook scripts` lists the hooks directory only — no help for a classpath: script. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "hooks:ean-check.kts|lm hook scripts",
        "classpath:mapping/no-such-script.kts|built into the hub"})
    void theRecommendationFitsTheScheme(String script, String recommended) throws Exception {
        ValidationResult r = service.validate(withKotlinCell(script));
        var refusal = r.errors().stream().filter(e -> e.path().equals("spec.mapping.cell[0].kotlin")).findFirst();
        assertTrue(refusal.isPresent(), () -> r.errors().toString());
        assertEquals(1, refusal.get().recommendations().size(), () -> refusal.get().toString());
        assertTrue(refusal.get().recommendations().get(0).contains(recommended), () -> refusal.get().toString());
    }

    @Test void aKotlinScriptThatCompiles_isAccepted() throws Exception {
        ValidationResult r = service.validate(withKotlinCell("classpath:mapping/uppercase-phone.kts"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /**
     * #1464: a tier written as a map (or a scalar) was read as no hooks — `lm apply` said ✓ and no hook ever ran. It
     * is refused at the tier, naming the shape. (The simplify review of #1402 had pinned only that it was no 500.)
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "dataset|{\"skip-empty-rows\": true}|object",
        "row|{\"map-columns\": {\"rename\": {\"a\": \"b\"}}}|object",
        "cell|{\"kotlin\": {\"script\": \"file:/hooks/ean-check.kts\"}}|object",
        "cell|\"upper\"|string"})
    void aTierThatIsNotAList_isRefusedAtTheTier(String tier, String json, String shape) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").set(tier, mapper.readTree(json));
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping." + tier)
                && e.message().equals(tier + " must be a list of hooks, e.g. - upper: { columns: [name] } (got "
                    + shape + ")")),
            () -> r.errors().toString());
    }

    @Test void aTierWrittenEmpty_isAccepted() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").putNull("cell");
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1514: a misspelt tier was never read — `lm validate` said valid and no hook ran. It is refused, by its path. */
    @ParameterizedTest
    @ValueSource(strings = {"cel", "rows", "on_error"})
    void anUnknownKeyUnderMapping_isRefusedNamingTheAcceptedOnes(String key) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").putArray(key).addObject()
            .putObject("upper").putArray("columns").add("name");
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping." + key)
                && e.message().equals("mapping: unknown key '" + key + "' — accepted: dataset, row, cell, on-error")),
            () -> r.errors().toString());
    }

    /**
     * #1403: every hook's parameters are checked at apply, through the hook's own check — the one the run makes
     * before its first row. Until then `lm apply` said ✓ to a mapping that could not run. One bad parameter per hook
     * that checks its parameters (map-columns has its own door, aggregator is refused whole).
     */
    @ParameterizedTest(name = "{0}[0].{1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "dataset|enrich-context|{\"tags\": [\"a\"]}|'tags' must be text, a number or a boolean",
        "dataset|filter-where|{\"column\": \"c\", \"regex\": \"[\"}|'regex' is not a valid regex",
        "row|defaults|{\"tags\": []}|'tags' must be text, a number or a boolean",
        "row|split-row|{\"explode\": \"tags\", \"escape\": \"?\"}|'escape' goes with 'by'",
        "cell|default|{\"column\": \"c\", \"value\": [\"a\"]}|'value' must be text, a number or a boolean",
        "cell|lookup|{\"column\": \"c\", \"table\": \"nope\"}|'table' must be a map",
        "cell|mask|{\"column\": \"c\", \"mask-char\": \"**\"}|'mask-char' must be exactly one character",
        "cell|parse-date|{\"column\": \"c\", \"zone\": \"Mars/Olympus\"}|parse-date hook:",
        "cell|parse-number|{\"column\": \"c\", \"integer\": \"yes\"}|'integer' must be true or false",
        "cell|regex-replace|{\"column\": \"c\", \"pattern\": \"[\"}|'pattern' is not a valid regex",
        "cell|regex-extract|{\"column\": \"c\", \"pattern\": \"(.*)\", \"groups\": {\"2\": \"d\"}}|is not a capture group",
        "cell|split|{\"column\": \"c\", \"into\": \"x\"}|'into'",
        "cell|to-uuid|{\"column\": \"c\", \"on-invalid\": \"bogus\"}|on-invalid",
        "cell|truncate|{\"column\": \"c\", \"length\": -1}|'length' must be >= 0",
        "cell|kotlin|{}|'script' is required"})
    void aHookParameterTheRunWouldRefuse_isRefusedAtApply(String tier, String hook, String params, String said)
            throws Exception {
        ValidationResult r = service.validate(withHook(tier, hook, params));
        String at = "spec.mapping." + tier + "[0]." + hook;
        assertTrue(r.errors().stream().anyMatch(e -> e.path().startsWith(at) && e.message().contains(said)),
            () -> "expected a refusal at " + at + " saying " + said + ", got: " + r.errors());
    }

    /**
     * #1487: a key the hook does not read is refused at apply, at its own path — map-columns too, whose own door
     * answers for its values but not for which keys it takes.
     */
    @ParameterizedTest(name = "{0}[0].{1}.{3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "dataset|sort-by|{\"columns\": [\"a\"], \"max_rows\": 10}|max_rows",
        "row|split-row|{\"by\": \"c\", \"separator\": \";\"}|separator",
        "row|map-columns|{\"renmae\": {\"a\": \"b\"}}|renmae",
        "cell|upper|{\"colums\": [\"a\"]}|colums",
        "cell|parse-number|{\"column\": \"c\", \"decimal_separator\": \",\"}|decimal_separator"})
    void aHookParameterTheHookDoesNotRead_isRefusedAtItsPath(String tier, String hook, String params, String key)
            throws Exception {
        ValidationResult r = service.validate(withHook(tier, hook, params));
        String at = "spec.mapping." + tier + "[0]." + hook + "." + key;
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals(at)
                && e.message().startsWith(hook + " hook: unknown parameter '" + key + "' — accepted: ")),
            () -> "expected a refusal at " + at + ", got: " + r.errors());
    }

    /**
     * #1492: a parameter block written as a scalar or a list validated as "no parameters" and the hook ran on its
     * defaults. Refused at the hook's path, naming the shape — map-columns too, whose own door answers for its values.
     */
    @ParameterizedTest(name = "{0}[0].{1}: {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "dataset|skip-empty-rows|true|boolean",
        "dataset|sort-by|[\"a\"]|array",
        "row|map-columns|\"a\"|string",
        "row|validate-row|[\"email\"]|array",
        "cell|upper|\"x\"|string",
        "cell|truncate|5|number"})
    void aParameterBlockThatIsNotAMap_isRefusedAtItsPath(String tier, String hook, String params, String shape)
            throws Exception {
        ValidationResult r = service.validate(withHook(tier, hook, params));
        String at = "spec.mapping." + tier + "[0]." + hook;
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals(at)
                && e.message().equals(hook + " hook: its parameters must be a map of name to value (got " + shape + ")")),
            () -> "expected a refusal at " + at + ", got: " + r.errors());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}"})
    void aBareOrEmptyParameterBlock_isAccepted(String params) throws Exception {
        ValidationResult r = service.validate(withHook("dataset", "skip-empty-rows", params));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dataset", "row", "cell"})
    void anUnknownHook_isRefusedAtApply(String tier) throws Exception {
        ValidationResult r = service.validate(withHook(tier, "fix-mojibak", "{}"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping." + tier + "[0].fix-mojibak")
                && e.message().equals("unknown " + tier + " hook: fix-mojibak")),
            () -> r.errors().toString());
    }

    @Test void aHookEntryWithTwoKeys_isRefusedAtApply() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").putArray("cell").add(mapper.readTree(
            "{\"upper\": {\"columns\": [\"c\"]}, \"lower\": {\"columns\": [\"c\"]}}"));
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0]")
            && e.message().equals("each hook must be an object with one key (cell[0]: got object with 2 keys)")),
            () -> r.errors().toString());
    }

    /**
     * #1403: every csv-file manifest the docs ship still validates — a refusal here is a manifest the docs promise
     * and the hub would not run.
     */
    @Test void everyShippedCsvManifest_validates() throws Exception {
        Path docs = Path.of("..", "docs");
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(docs)) {
            manifests = files.filter(p -> p.toString().endsWith(".yaml"))
                .filter(p -> read(p).contains("connector_type: csv-file")).sorted().toList();
        }
        assertFalse(manifests.isEmpty(), "the sweep must find the manifests it polices");
        List<String> refused = new ArrayList<>();
        for (Path p : manifests) {
            ValidationResult r = service.validate(mapper.valueToTree(new Yaml().load(read(p))));
            if (!r.isValid()) refused.add(docs.relativize(p) + " " + r.errors());
        }
        assertTrue(refused.isEmpty(), () -> String.join("\n", refused));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode withHook(String tier, String hook, String params) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").putArray(tier).addObject()
            .set(hook, mapper.readTree(params));
        return doc;
    }

    /** #1408: on-error is read at apply, so a mode the run would not know is refused there. */
    @Test void onErrorWithAKnownMode_isAccepted() throws Exception {
        ValidationResult r = service.validate(withOnError("dlq"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    @Test void onErrorWithAnUnknownMode_isRefused() throws Exception {
        ValidationResult r = service.validate(withOnError("quarantine"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.on-error")),
            () -> "expected a refusal of spec.mapping.on-error, got: " + r.errors());
    }

    /** Review of #1409: dlq would drop fanned fragments one by one, like validate-row after split-row. */
    @Test void onErrorDlqWithSplitRow_isRefused() throws Exception {
        ObjectNode doc = withOnError("dlq");
        ((ObjectNode) doc.path("spec").path("mapping")).putArray("row").addObject()
            .putObject("split-row").put("by", "tags");
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.on-error")
                && e.message().contains("split-row")),
            () -> "expected a dlq-with-split-row refusal, got: " + r.errors());
    }

    @Test void onErrorFailWithSplitRow_isAccepted() throws Exception {
        ObjectNode doc = withOnError("fail");
        ((ObjectNode) doc.path("spec").path("mapping")).putArray("row").addObject()
            .putObject("split-row").put("by", "tags");
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1415: lines: true is a parser key of its own; alone, or with one renamed column, it is valid. */
    @ParameterizedTest
    @ValueSource(strings = {"", "message"})
    void lines_aloneOrWithOneColumn_isAccepted(String column) throws Exception {
        ObjectNode doc = withLines();
        if (!column.isEmpty()) ((ObjectNode) doc.path("spec").path("parser")).putArray("columns").add(column);
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertTrue(r.warnings().stream().noneMatch(w -> w.path().contains("lines")),
            () -> "lines must be a known parser key, got: " + r.warnings());
    }

    /** lines: true means no delimiter, no header and one column — a manifest saying otherwise is refused. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"delimiter|\";\"", "has_header|true", "has_header|false",
        "columns|[\"a\", \"b\"]"})
    void lines_withAConflictingParserKey_isRefused(String key, String json) throws Exception {
        ObjectNode doc = withLines();
        ((ObjectNode) doc.path("spec").path("parser")).set(key, mapper.readTree(json));
        ValidationResult r = service.validate(doc);
        // One refusal, naming the key: has_header: false must not also trip the headerless rule's "columns required".
        assertEquals(List.of("spec.parser." + key), r.errors().stream().map(e -> e.path()).toList(),
            () -> "expected exactly a refusal of spec.parser." + key + ", got: " + r.errors());
    }

    /** A `delimiter:` left empty is no delimiter, for the runtime as for this rule. */
    @Test void lines_withAnEmptyDelimiter_isAccepted() throws Exception {
        ObjectNode doc = withLines();
        ((ObjectNode) doc.path("spec").path("parser")).putNull("delimiter");
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** The unit separator is the reader's sign of line mode: declared by hand, it would switch quoting off. */
    @Test void theUnitSeparatorAsADelimiter_isRefused() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("parser").put("delimiter", "\u001F");
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.parser.delimiter")
                && e.message().contains("lines: true")),
            () -> "expected the unit separator refused, got: " + r.errors());
    }

    /** A connector whose spec.mapping is {@code mappingJson}; {@code lines}: the source has the one column `line`. */
    private ObjectNode withMapping(String mappingJson, boolean lines) throws Exception {
        ObjectNode doc = lines ? withLines() : (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).set("mapping", mapper.readTree(mappingJson));
        return doc;
    }

    private static final String EDIFACT_CELL = "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^([A-Z]{3})\\\\+?(.*)$\","
        + "\"groups\":{\"1\":\"segment\",\"2\":\"elements\"}}},"
        + "{\"regex-extract\":{\"column\":\"elements\",\"pattern\":\"^\\\\d+\\\\+\\\\+(\\\\d+)\","
        + "\"groups\":{\"1\":\"ean\"}}}";

    /** #1452: the docs' EDIFACT mapping validates clean — every column fill-down names is produced before it. */
    @Test void fillDown_overProducedColumns_isAccepted() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"row\":[{\"split-row\":{\"by\":\"line\",\"sep\":\"'\","
            + "\"escape\":\"?\",\"position\":\"seg_no\"}}],\"cell\":[" + EDIFACT_CELL + ","
            + "{\"fill-down\":{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"segment\",\"in\":[\"LIN\",\"UNT\"]}}}]}",
            true));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1452: sort-by sorts one chunk — fill-down follows file order, which a sort has lost. */
    @Test void fillDown_besideSortBy_isRefused() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"dataset\":[{\"sort-by\":{\"columns\":[\"line\"]}}],"
            + "\"cell\":[{\"fill-down\":{\"columns\":[\"line\"]}}]}", true));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].fill-down")
                && e.message().contains("sort-by")),
            () -> "expected fill-down refused beside sort-by, got: " + r.errors());
    }

    /** #1452: two declarations carrying one column would race on one cell. */
    @Test void fillDown_columnClaimedTwice_isRefused() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":[" + EDIFACT_CELL + ","
            + "{\"fill-down\":{\"columns\":[\"segment\",\"ean\"]}},{\"fill-down\":{\"columns\":[\"ean\"]}}]}", true));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[3].fill-down.columns[0]")
                && e.message().contains("'ean'") && e.message().contains("cell[2]")),
            () -> "expected the second claim on ean refused, got: " + r.errors());
    }

    /**
     * #1452: with `lines: true` the source has one column, `line` — a fill-down column or reset column that no earlier
     * hook produces is refused at validate, by its path.
     */
    @Test void fillDown_overAColumnNothingProduces_isRefusedWhenKnowable() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":[" + EDIFACT_CELL + ","
            + "{\"fill-down\":{\"columns\":[\"order_no\"],\"reset-when\":{\"column\":\"tag\",\"in\":[\"UNH\"]}}}]}",
            true));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[2].fill-down.columns[0]")
                && e.message().contains("'order_no'")), () -> "expected order_no refused, got: " + r.errors());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[2].fill-down.reset-when.column")
                && e.message().contains("'tag'")), () -> "expected tag refused, got: " + r.errors());
    }

    /**
     * #1504: regex-extract writes null on every row it misses — a second one writing the same column erases what the
     * first read (an IDoc's DOCNUM, read from the control record, then nulled by the data records' pattern). Refused
     * by the group's path, naming the first; whatever the source's columns, the groups are in the manifest.
     */
    @Test void regexExtract_writingAColumnAnEarlierOneWrites_isRefused() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":["
            + "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^EDI_DC40.{5}(\\\\d{16})\",\"groups\":{\"1\":\"docnum\"}}},"
            + "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^E2.{31}(\\\\d{16})(\\\\d{6})\","
            + "\"groups\":{\"1\":\"docnum\",\"2\":\"segnum\"}}}]}", false));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[1].regex-extract.groups.1")
                && e.message().contains("'docnum'") && e.message().contains("cell[0]")),
            () -> "expected the second writer of docnum refused, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.message().contains("'segnum'")), () -> "segnum: " + r.errors());
    }

    /** #1504: one pattern for both shapes, an alternation, writes each column once — valid. */
    @Test void regexExtract_oneAlternationForTwoShapes_isAccepted() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":[{\"regex-extract\":{\"column\":\"line\","
            + "\"pattern\":\"^(?:EDI_DC40.{5}|E2.{31})(\\\\d{16})(\\\\d{6})?\",\"groups\":{\"1\":\"docnum\",\"2\":\"segnum\"}}},"
            + "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^E2EDP19.{56}(\\\\d{3})\",\"groups\":{\"1\":\"qualifier\"}}}]}",
            true));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** With a header read at run time the columns are not known at apply: the run checks them, validate does not. */
    @Test void fillDown_overAHeaderColumn_isLeftToTheRun() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":[{\"fill-down\":{\"columns\":[\"group_name\"]}}]}",
            false));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1505: a group writing the column it reads would null it on every row it misses — refused whatever the source. */
    @Test void regexExtract_writingItsOwnColumn_isRefused() throws Exception {
        ValidationResult r = service.validate(withMapping("{\"cell\":[{\"regex-extract\":{\"column\":\"ref\","
            + "\"pattern\":\"(\\\\d+)\",\"groups\":{\"1\":\"ref\"}}}]}", false));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].regex-extract")
                && e.message().contains("'ref'") && e.message().contains("the column it reads")),
            () -> "expected ref refused, got: " + r.errors());
    }

    /**
     * #1505: with the source's columns known, a group naming a column already in the row — the file's, or one an
     * earlier hook added — is refused; a column an earlier regex-extract writes is #1504's, said once.
     */
    @Test void regexExtract_writingAColumnAlreadyInTheRow_isRefusedWhenKnowable() throws Exception {
        ValidationResult source = service.validate(headerless("{\"cell\":[{\"regex-extract\":{\"column\":\"status\","
            + "\"pattern\":\"^([A-Z]+)\",\"groups\":{\"1\":\"code\"}}}]}"));
        assertTrue(source.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].regex-extract.groups.1")
                && e.message().contains("'code'") && e.message().contains("already in the row")),
            () -> "expected the file's code refused, got: " + source.errors());

        ValidationResult added = service.validate(withMapping("{\"dataset\":[{\"enrich-context\":{\"source_system\":\"erp\"}}],"
            + "\"cell\":[{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^(\\\\w+)\",\"groups\":{\"1\":\"source_system\"}}}]}",
            true));
        assertTrue(added.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].regex-extract.groups.1")
                && e.message().contains("'source_system'")), () -> "expected source_system refused, got: " + added.errors());

        ValidationResult twice = service.validate(withMapping("{\"cell\":["
            + "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^A(\\\\d+)\",\"groups\":{\"1\":\"n\"}}},"
            + "{\"regex-extract\":{\"column\":\"line\",\"pattern\":\"^B(\\\\d+)\",\"groups\":{\"1\":\"n\"}}}]}", true));
        assertEquals(1, twice.errors().stream().filter(e -> e.message().contains("'n'")).count(),
            () -> "expected the second writer of n refused once, got: " + twice.errors());
    }

    /** A headerless file whose columns are declared — `code, status, amount` — under {@code mappingJson}. */
    private ObjectNode headerless(String mappingJson) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ObjectNode spec = (ObjectNode) doc.path("spec");
        spec.set("parser", mapper.readTree("{\"has_header\":false,\"delimiter\":\",\",\"columns\":[\"code\",\"status\",\"amount\"]}"));
        spec.set("mapping", mapper.readTree(mappingJson));
        return doc;
    }

    /**
     * #1500: with the source's columns known, a hook reading a column that does not exist at that point is refused,
     * by its exact path — it used to validate clean and do nothing (filter-where dropped every row).
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "{\"dataset\":[{\"filter-where\":{\"column\":\"statut\",\"equals\":\"OPEN\"}}]}       | spec.mapping.dataset[0].filter-where.column      | statut",
        "{\"dataset\":[{\"sort-by\":{\"columns\":[\"code\",\"cdoe\"]}}]}                       | spec.mapping.dataset[0].sort-by.columns[1]       | cdoe",
        "{\"row\":[{\"validate-row\":{\"required\":[\"code\",\"nmae\"]}}]}                     | spec.mapping.row[0].validate-row.required[1]     | nmae",
        "{\"row\":[{\"coalesce\":{\"column\":\"c\",\"from\":[\"x\"]}}]}                         | spec.mapping.row[0].coalesce.from                | x",
        "{\"row\":[{\"map-columns\":{\"drop\":[\"stauts\"]}}]}                                   | spec.mapping.row[0].map-columns.drop[0]          | stauts",
        "{\"cell\":[{\"upper\":{\"columns\":[\"stauts\"]}}]}                                     | spec.mapping.cell[0].upper.columns[0]            | stauts",
        "{\"cell\":[{\"parse-date\":{\"column\":\"dat\",\"formats\":[\"yyyy\"]}}]}               | spec.mapping.cell[0].parse-date.column           | dat",
        "{\"cell\":[{\"fill-down\":{\"columns\":[\"code\"],\"reset-when\":{\"column\":\"tag\",\"in\":[\"H\"]}}}]} | spec.mapping.cell[0].fill-down.reset-when.column | tag",
        "{\"cell\":[{\"to-uuid\":{\"column\":\"cdoe\"}}]}                                    | spec.mapping.cell[0].to-uuid.column            | cdoe",
        "{\"on-error\":{\"fields\":{\"amout\":\"dlq\"}}}                                          | spec.mapping.on-error.fields.amout               | amout",
        "{\"row\":[{\"map-columns\":{\"rename\":{\"status\":\"state\"}}}],\"cell\":[{\"upper\":{\"columns\":[\"status\"]}}]} | spec.mapping.cell[0].upper.columns[0] | status"})
    void aHookReadingAColumnThatDoesNotExist_isRefused(String mapping, String path, String column) throws Exception {
        ValidationResult r = service.validate(headerless(mapping));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals(path) && e.message().contains("'" + column + "'")),
            () -> "expected " + path + " refused, got: " + r.errors());
    }

    /** #1500: the same mappings with the right names validate clean — a renamed column is read under its new name. */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"dataset\":[{\"filter-where\":{\"column\":\"status\",\"equals\":\"OPEN\"}},{\"sort-by\":{\"columns\":[\"code\"]}}]}",
        "{\"row\":[{\"map-columns\":{\"rename\":{\"status\":\"state\"},\"drop\":[\"amount\"]}},{\"validate-row\":{\"required\":[\"state\"]}}],"
            + "\"cell\":[{\"upper\":{\"columns\":[\"state\"]}}]}",
        "{\"cell\":[{\"regex-extract\":{\"column\":\"code\",\"pattern\":\"^(\\\\w)\",\"groups\":{\"1\":\"first\"}}},{\"upper\":{\"columns\":[\"first\"]}}],"
            + "\"on-error\":{\"fields\":{\"first\":\"dlq\"}}}",
        "{\"cell\":[{\"to-uuid\":{\"column\":\"id_ext\",\"on-invalid\":\"generate\",\"generate-from\":\"code\"}},{\"upper\":{\"columns\":[\"id_ext\"]}}]}",
        // map-columns runs its renames one after another, then its drops (Sergey's review of #1501)
        "{\"row\":[{\"map-columns\":{\"rename\":{\"status\":\"state\"},\"drop\":[\"state\"]}}]}",
        "{\"row\":[{\"map-columns\":{\"rename\":{\"status\":\"state\",\"state\":\"phase\"}}}],\"cell\":[{\"upper\":{\"columns\":[\"phase\"]}}]}"})
    void theSameHooksOverColumnsThatExist_validateClean(String mapping) throws Exception {
        ValidationResult r = service.validate(headerless(mapping));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1500: an index on a column the mapping does not end with would index nothing — refused by its path. */
    @Test void anIndexOnAColumnThatDoesNotExist_isRefused() throws Exception {
        ObjectNode doc = headerless("{\"row\":[{\"map-columns\":{\"rename\":{\"status\":\"state\"}}}]}");
        ((ObjectNode) doc.path("spec")).set("target", mapper.readTree("{\"indexes\":[{\"columns\":[\"code\",\"status\"]}]}"));
        ValidationResult r = service.validate(doc);
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.target.indexes[0].columns[1]")
                && e.message().contains("'status'")), () -> "expected status refused, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().equals("spec.target.indexes[0].columns[0]")),
            () -> "code exists, got: " + r.errors());
    }

    /** #1500: a column dropped is gone for the hooks after the drop. */
    @Test void aColumnDropped_isRefusedToTheHooksAfter() throws Exception {
        ValidationResult r = service.validate(headerless("{\"row\":[{\"map-columns\":{\"drop\":[\"amount\"]}}],"
            + "\"cell\":[{\"parse-number\":{\"column\":\"amount\"}}]}"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].parse-number.column")),
            () -> "expected amount refused after its drop, got: " + r.errors());
    }

    /** #1500: after a Kotlin script the columns are unknown — nothing is said; a hook before it is still checked. */
    @Test void afterAKotlinHook_nothingIsSaid_beforeIt_stillChecked() throws Exception {
        ValidationResult r = service.validate(headerless("{\"cell\":[{\"upper\":{\"columns\":[\"nope1\"]}},"
            + "{\"kotlin\":{\"script\":\"classpath:mapping/uppercase-phone.kts\"}},{\"upper\":{\"columns\":[\"nope2\"]}}]}"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping.cell[0].upper.columns[0]")),
            () -> "the hook before Kotlin is checked, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().startsWith("spec.mapping.cell[2]")),
            () -> "nothing after Kotlin, got: " + r.errors());
    }

    /** #1500: with a header read at run time, the columns are not known at apply — nothing is said. */
    @Test void withAHeaderReadAtRunTime_nothingIsSaid() throws Exception {
        ValidationResult r = service.validate(withHook("cell", "upper", "{\"columns\":[\"anything\"]}"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    private ObjectNode withLines() throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("parser").put("lines", true);
        return doc;
    }

    private ObjectNode withOnError(String mode) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        ((ObjectNode) doc.path("spec")).putObject("mapping").putObject("on-error")
            .putObject("fields").put("amount_eur", mode);
        return doc;
    }

    private ObjectNode withKotlinCell(String... scripts) throws Exception {
        ObjectNode doc = (ObjectNode) load("csv-local-valid.yaml");
        var cell = ((ObjectNode) doc.path("spec")).putObject("mapping").putArray("cell");
        for (String script : scripts) cell.addObject().putObject("kotlin").put("script", script);
        return doc;
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
        // lm csv ingest has no after_process option: the refusal points at no gesture that does not exist.
        assertTrue(r.errors().stream().noneMatch(e -> e.message().contains("lm csv ingest")), () -> r.errors().toString());
    }

    // Connector runs never read the transport hooks or max_file_size; a connector manifest
    // carrying them would be silently ignored on every scheduled run.
    @Test void transportHooksInConnectorManifest_areRefused() throws Exception {
        ValidationResult r = service.validate(load("csv-transport-hooks.yaml"));
        assertFalse(r.isValid());
        for (String key : new String[] {"file_filter", "pre_process", "max_file_size"}) {
            String path = "spec.transport." + key;
            assertTrue(r.errors().stream().anyMatch(e -> e.path().equals(path)),
                () -> "expected a refusal at " + path + ", got: " + r.errors());
            // One report per key: the refusal, not the refusal and then "unknown key" for the same key.
            assertEquals(1, Stream.concat(r.errors().stream(), r.warnings().stream())
                    .filter(e -> e.path().equals(path)).count(),
                () -> "one report at " + path + ", got: " + r.errors() + " / " + r.warnings());
        }
        // lm csv ingest has no such flag: the refusal points at no gesture that does not exist.
        assertTrue(r.errors().stream().noneMatch(e -> e.message().contains("lm csv ingest")
                || e.recommendations().stream().anyMatch(h -> h.contains("lm csv ingest"))),
            () -> r.errors().toString());
    }
}
