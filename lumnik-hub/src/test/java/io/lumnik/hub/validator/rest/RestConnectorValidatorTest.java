package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.discovery.OpenApiImporter;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.FieldSpec;
import io.lumnik.hub.validator.ConnectorValidationService;
import io.lumnik.hub.testsupport.ValidationWarnings;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RestConnectorValidatorTest {

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper mapper;

    private JsonNode load(String name) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/validator/" + name)) {
            Object loaded = new Yaml().load(is);
            return mapper.valueToTree(loaded);
        }
    }

    // The runtime parses default_rate_limit on the first request of a run; before this rule an
    // unparseable value passed validate, passed apply, and crashed the run instead.
    // ── #552 probes: a key written with nothing after it, at the endpoint cross-field rules ──

    private static final String REST_HEAD = """
        {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
         "metadata":{"name":"empty-key","connector_type":"rest-generic","scopes":["test"]},
         "spec":{"base_url":"https://api.example.com","endpoints":[%s]}}""";

    private static final String EP_TAIL =
        "\"pagination\":{\"kind\":\"cursor\",\"cursor_param\":\"after\","
        + "\"cursor_path\":\"$.data[-1:].id\",\"has_more_path\":\"$.has_more\","
        + "\"response_path\":\"$.data\"},"
        + "\"target\":{\"kind\":\"hybrid\",\"table\":\"ext.contacts\",\"unique_key\":\"$.id\"}";

    /** #1437: rest-generic transforms through promote[].transform only, so a mapping block would never run. Refused. */
    @Test void mappingBlock_isRefused() throws Exception {
        JsonNode doc = mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\"," + EP_TAIL + "}"));
        ((ObjectNode) doc.path("spec")).set("mapping", mapper.readTree("{\"cell\":[{\"kotlin\":{\"script\":\"hooks:check.kts\"}}]}"));
        ValidationResult r = service.validate(doc);

        assertFalse(r.isValid(), "a mapping block on rest-generic must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.mapping")
            && String.join(" ", e.recommendations()).contains("promote")), () -> "refused by name, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), "refused once, not also warned as unknown");
    }

    private static final String GRAPHQL_EP =
        "{\"id\":\"partners\",\"kind\":\"graphql\",\"query\":\"query($after: String) { x }\","
        + "\"pagination\":{\"kind\":\"relay\",\"cursor_binding\":\"variable\",\"cursor_var\":\"after\","
        + "\"cursor_path\":\"$.data.x.pageInfo.endCursor\",\"has_more_path\":\"$.data.x.pageInfo.hasNextPage\","
        + "\"response_path\":\"$.data.x.nodes\"},"
        + "\"target\":{\"table\":\"ext.partners\",\"unique_key\":\"$.id\"}";

    private static final String SOAP_EP =
        "{\"id\":\"customers\",\"kind\":\"soap\",\"path\":\"/svc\",\"soap_action\":\"A\","
        + "\"body\":\"<In><PageSize>{{page_size}}</PageSize></In>\","
        + "\"pagination\":{\"kind\":\"soap\",\"page_size\":100,\"response_path\":\"$.Body.Out.Item\"},"
        + "\"target\":{\"table\":\"ext.cust\",\"unique_key\":\"$.Id\"}";

    /** #1438: the per-endpoint key set was open — a misspelled key validated clean and was dropped. Each shape names it. */
    @Test void unknownEndpointKey_isNamedByPath_inEveryRequestShape() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\",\"incremetal\":{}," + EP_TAIL + "},"
            + GRAPHQL_EP + ",\"variabels\":{}},"
            + SOAP_EP + ",\"soapaction\":\"B\"}")));

        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.endpoints[0].incremetal", "spec.endpoints[1].variabels", "spec.endpoints[2].soapaction"),
            ValidationWarnings.unknownKeyPaths(r), () -> "each typo named by its path, got: " + r.warnings());
    }

    /** #1438: the anti-lie guard of the closed endpoint — every key each shape's runtime reads is declared. */
    @Test void everyKeyTheRuntimeReads_isNotCalledUnknown() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"kind\":\"rest\",\"method\":\"GET\",\"path\":\"/v1/contacts\","
            + "\"incremental\":{\"mode\":\"full\"}," + EP_TAIL + "},"
            + GRAPHQL_EP + ",\"variables\":{\"locale\":\"fr-FR\"},\"incremental\":{\"mode\":\"full\"}},"
            + SOAP_EP + ",\"header\":\"<T/>\",\"incremental\":{\"mode\":\"full\"}}")));

        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), () -> "no honored key may be called unknown: " + r.warnings());
    }

    private static final String GRAPHQL_WATERMARK =
        ",\"incremental\":{\"mode\":\"watermark\",\"param\":\"updated_since\",\"watermark_path\":\"$.updated_at\"}}";

    /** #1543: on a graphql endpoint the mark rides in the variable named by param — the query must declare it. */
    @Test void graphqlWatermark_paramNotDeclaredInTheQuery_isError() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(GRAPHQL_EP + GRAPHQL_WATERMARK)));

        assertFalse(r.isValid(), "a mark sent as a variable the query never declares would be refused by the server");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.endpoints[0].incremental.param")
            && e.message().contains("$updated_since")), () -> "refused by path, naming the variable, got: " + r.errors());
    }

    @Test void graphqlWatermark_paramDeclaredInTheQuery_isValid() throws Exception {
        String declared = GRAPHQL_EP.replace("query($after: String)", "query($after: String, $updated_since: String)");
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(declared + GRAPHQL_WATERMARK)));

        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
    }

    /** #1437 at the endpoint tier (#1438): a mapping block there would never run either. Refused by path, not also warned. */
    @Test void endpointMappingBlock_isRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\","
            + "\"mapping\":{\"cell\":[{\"kotlin\":{\"script\":\"hooks:check.kts\"}}]}," + EP_TAIL + "}")));

        assertFalse(r.isValid(), "a mapping block on a rest-generic endpoint must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.endpoints[0].mapping")
            && String.join(" ", e.recommendations()).contains("promote")), () -> "refused by path, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), "refused once, not also warned as unknown");
    }

    /** A connector whose spec.defaults is {@code defaultsJson}, over one REST endpoint. */
    private ValidationResult validateWithDefaults(String defaultsJson) throws Exception {
        return validateWithDefaults("{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\"," + EP_TAIL + "}",
            defaultsJson);
    }

    /** A connector of {@code endpointsJson} (the endpoints list's members) whose spec.defaults is {@code defaultsJson}. */
    private ValidationResult validateWithDefaults(String endpointsJson, String defaultsJson) throws Exception {
        JsonNode doc = mapper.readTree(REST_HEAD.formatted(endpointsJson));
        ((ObjectNode) doc.path("spec")).set("defaults", mapper.readTree(defaultsJson));
        return service.validate(doc);
    }

    /**
     * #1461: spec.defaults is laid under every endpoint, so its keys are per-endpoint keys — of any request shape.
     * A typo is named by its path; a key one of the shapes reads is not.
     */
    @Test void unknownDefaultsKey_isNamedByPath() throws Exception {
        ValidationResult r = validateWithDefaults(
            "{\"incremetal\":{},\"incremental\":{\"mode\":\"full\"},\"variables\":{},\"soap_action\":\"A\"}");

        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.defaults.incremetal"), ValidationWarnings.unknownKeyPaths(r),
            () -> "the typo named by its path, got: " + r.warnings());
    }

    /** #1437 at spec.defaults (#1461): flattened under every endpoint, a mapping block there would never run either. */
    @Test void defaultsMappingBlock_isRefused() throws Exception {
        ValidationResult r = validateWithDefaults("{\"mapping\":{\"cell\":[{\"kotlin\":{\"script\":\"hooks:check.kts\"}}]}}");

        assertFalse(r.isValid(), "a mapping block in spec.defaults must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.defaults.mapping")
            && String.join(" ", e.recommendations()).contains("promote")), () -> "refused by path, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), "refused once, not also warned as unknown");
    }

    /** The endpoint tier's headers refusal holds in spec.defaults too (#1461). */
    @Test void defaultsHeaders_areRefused() throws Exception {
        ValidationResult r = validateWithDefaults("{\"headers\":[{\"name\":\"X-A\",\"value\":\"1\"}]}");

        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.defaults.headers")),
            () -> "headers refused by path, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), () -> "not also unknown: " + r.warnings());
    }

    /**
     * #1471: a connector-level key in spec.defaults is refused, not sent up with a warning as on an endpoint — spec's
     * own value wins over it, and without one it would run unchecked. A literal bearer token there escaped every auth
     * check, the #530 warning included.
     */
    @Test void defaultsConnectorLevelKeys_areRefused() throws Exception {
        ValidationResult r = validateWithDefaults("""
            {"base_url":"https://other.example.com","auth":{"kind":"bearer","token_value":"s3cr3t"},
             "default_rate_limit":"5/s","graphql_path":"/gql"}""");

        assertFalse(r.isValid(), "connector-level keys in spec.defaults must be refused");
        assertEquals(List.of("spec.defaults.auth", "spec.defaults.base_url", "spec.defaults.default_rate_limit",
                "spec.defaults.graphql_path"),
            r.errors().stream().map(ValidationError::path).sorted().toList(), () -> "each refused by path: " + r.errors());
        assertTrue(r.errors().stream().allMatch(e -> String.join(" ", e.recommendations()).contains("spec.")),
            () -> "each sent up to spec: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), () -> "refused once, not also unknown: " + r.warnings());
    }

    /**
     * #1471: the run decides an endpoint's request shape from the merged config, the validator from the endpoint's own
     * node — so defaults.query made every endpoint GraphQL and defaults.kind every kind-less one SOAP, unseen. Refused.
     */
    @Test void defaultsShapeDiscriminators_areRefused() throws Exception {
        ValidationResult r = validateWithDefaults("{\"kind\":\"soap\",\"query\":\"{ a }\"}");

        assertFalse(r.isValid(), "a request-shape key in spec.defaults must be refused");
        assertEquals(List.of("spec.defaults.kind", "spec.defaults.query"),
            r.errors().stream().map(ValidationError::path).sorted().toList(), () -> "each refused by path: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), () -> "refused once, not also unknown: " + r.warnings());
    }

    /** The declared spec.defaults key set says what validation does: every refused key is declared as refused. */
    @Test void defaultsDef_declaresTheRefusedKeysAsRefused() {
        var refused = new ArrayList<>(EndpointsBlockValidator.CONNECTOR_LEVEL_KEYS);
        refused.addAll(EndpointsBlockValidator.SHAPE_KEYS);
        Map<String, String> docs = EndpointsBlockValidator.defaultsDef().fields().stream()
            .collect(Collectors.toMap(FieldSpec::name, FieldSpec::doc));
        for (String key : refused) {
            assertTrue(docs.getOrDefault(key, "").startsWith("Not accepted in spec.defaults"),
                () -> key + " declared as: " + docs.get(key));
        }
    }

    /**
     * #1471: keys every endpoint carries itself are never read from spec.defaults — id, method and path come from the
     * endpoint's own node, and the endpoint's pagination, target and SOAP body replace the defaults' whole. Named as
     * unknown, like any other key the run ignores (#1461), instead of validating clean.
     */
    @Test void defaultsKeysTheRunNeverReads_areNamedUnknown() throws Exception {
        ValidationResult r = validateWithDefaults("""
            {"id":"x","method":"POST","path":"/p","pagination":{"kind":"page"},
             "target":{"table":"ext.t","unique_key":"$.id"},"body":"<In/>","header":"<T/>"}""");

        assertTrue(r.isValid(), () -> "an unread key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.defaults.body", "spec.defaults.id", "spec.defaults.method", "spec.defaults.pagination",
                "spec.defaults.path", "spec.defaults.target"),
            ValidationWarnings.unknownKeyPaths(r), () -> "each named by its path, got: " + r.warnings());
    }

    /** Two REST endpoints, the first carrying {@code ownKeys} (JSON members, may be empty), under {@code defaultsJson}. */
    private ValidationResult validateTwoEndpointsWithDefaults(String defaultsJson, String ownKeys) throws Exception {
        return validateWithDefaults(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\"," + ownKeys + EP_TAIL + "},"
            + "{\"id\":\"orders\",\"method\":\"GET\",\"path\":\"/v1/orders\"," + EP_TAIL + "}", defaultsJson);
    }

    /**
     * #1481: each endpoint is validated merged with the defaults it inherits — what the run executes. A default's
     * mistake is named once, at the path the integrator wrote, however many endpoints inherit it.
     */
    @Test void defaultsIncremental_isCheckedOnceAtItsOwnPath() throws Exception {
        ValidationResult r = validateTwoEndpointsWithDefaults("{\"incremental\":{\"mode\":\"watermark\"}}", "");

        assertEquals(List.of("spec.defaults.incremental.param", "spec.defaults.incremental.watermark_path"),
            r.errors().stream().map(ValidationError::path).toList(), () -> "named once at its path: " + r.errors());
    }

    /** A default the endpoint replaces is not what that endpoint runs: reported for the endpoints that inherit it only. */
    @Test void defaultReplacedByEveryEndpoint_isNotReportedForThem() throws Exception {
        ValidationResult inherited = validateTwoEndpointsWithDefaults(
            "{\"incremental\":{\"mode\":\"bogus\"}}", "\"incremental\":{\"mode\":\"full\"},");
        assertEquals(List.of("spec.defaults.incremental.mode"),
            inherited.errors().stream().map(ValidationError::path).toList(), () -> "orders inherits it: " + inherited.errors());

        ValidationResult replaced = validateWithDefaults(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\",\"incremental\":{\"mode\":\"full\"},"
            + EP_TAIL + "}", "{\"incremental\":{\"mode\":\"bogus\"}}");
        assertTrue(replaced.isValid(), () -> "no endpoint runs it: " + replaced.errors());
    }

    /** The docs' spec.defaults example (rest.md, "Settings shared by every endpoint"): valid, nothing warned. */
    @Test void docsDefaultsExample_validatesClean() throws Exception {
        ValidationResult r = validateTwoEndpointsWithDefaults(
            "{\"incremental\":{\"mode\":\"watermark\",\"param\":\"updated_since\",\"watermark_path\":\"$.updated_at\"}}",
            "\"incremental\":{\"mode\":\"full\"},");

        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertEquals(List.of(), r.warnings().stream().filter(w -> w.path().startsWith("spec.")).toList());
    }

    /** A malformed default is refused once, not once by the defaults' declaration and again per inheriting endpoint. */
    @Test void scalarDefaultsIncremental_isRefusedOnce() throws Exception {
        ValidationResult r = validateTwoEndpointsWithDefaults("{\"incremental\":\"watermark\"}", "");

        assertEquals(List.of("spec.defaults.incremental"),
            r.errors().stream().map(ValidationError::path).toList(), () -> "refused once: " + r.errors());
    }

    /** Every key spec.defaults accepts is inherited by the endpoints that read it, or refused there (#1481). */
    @Test void everyAcceptedDefaultsKey_isInheritedOrRefused() {
        var accepted = EndpointsBlockValidator.defaultsDef().fields().stream().map(FieldSpec::name)
            .collect(Collectors.toCollection(TreeSet::new));
        accepted.removeAll(EndpointsBlockValidator.CONNECTOR_LEVEL_KEYS);
        accepted.removeAll(EndpointsBlockValidator.SHAPE_KEYS);
        accepted.removeAll(List.of("headers", "mapping"));
        assertEquals(new TreeSet<>(EndpointsBlockValidator.INHERITED_FROM_DEFAULTS), accepted);
    }

    /** The SOAP header template is inherited by a SOAP endpoint: its unknown placeholder is checked, at defaults.header. */
    @Test void defaultsSoapHeader_isCheckedForTheSoapEndpointsThatInheritIt() throws Exception {
        ValidationResult r = validateWithDefaults(SOAP_EP + "}", "{\"header\":\"<T>{{tokn}}</T>\"}");

        assertEquals(List.of("spec.defaults.header"), r.errors().stream().map(ValidationError::path).toList(),
            () -> "the placeholder named at its path: " + r.errors());
    }

    /**
     * The run lays the endpoint over the connector, so a connector-level key written on an endpoint is read there —
     * calling it "ignored by the runtime" would lie. It is named and sent back up to spec, where it is checked.
     */
    @Test void connectorLevelKeyOnAnEndpoint_isNamedAndSentUpToSpec() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\","
            + "\"base_url\":\"https://other.example.com\"," + EP_TAIL + "}")));

        assertTrue(r.isValid(), () -> "a warning, not a refusal: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), () -> "read by the run, so not unknown: " + r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w -> w.path().equals("spec.endpoints[0].base_url")
            && String.join(" ", w.recommendations()).contains("spec.base_url")), () -> "named by path, got: " + r.warnings());
    }

    /** #1438: closing the endpoint must not accuse a manifest the docs ship — every rest-generic example stays clean. */
    @Test void everyRestExampleUnderDocs_validatesClean() throws Exception {
        Path repo = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(repo.resolve("docs"))) {
            files = walk.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList();
        }
        List<String> findings = new ArrayList<>();
        int checked = 0;
        for (Path f : files) {
            String text = read(f);
            if (!text.contains("rest-generic")) continue;
            for (Object loaded : new Yaml().loadAll(text)) {
                JsonNode doc = mapper.valueToTree(loaded);
                if (!"rest-generic".equals(doc.path("metadata").path("connector_type").asText())) continue;
                checked++;
                ValidationResult r = service.validate(doc);
                String rel = repo.relativize(f).toString();
                r.errors().forEach(e -> findings.add(rel + ": " + e.path() + " " + e.message()));
                ValidationWarnings.unknownKeyPaths(r).forEach(p -> findings.add(rel + ": unknown " + p));
            }
        }
        assertTrue(checked > 0, "no rest-generic manifest found under docs/ — the walk looks in the wrong place");
        assertEquals(List.of(), findings);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test void endpointHeadersWrittenEmpty_isNotRefused() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\","
            + "\"headers\":null," + EP_TAIL + "}")));

        assertTrue(r.isValid(), () -> "headers declaring nothing must not be refused, got: " + r.errors());
    }

    @Test void endpointQueryWrittenEmpty_staysARestEndpoint() throws Exception {
        ValidationResult r = service.validate(mapper.readTree(REST_HEAD.formatted(
            "{\"id\":\"contacts\",\"method\":\"GET\",\"path\":\"/v1/contacts\","
            + "\"query\":null," + EP_TAIL + "}")));

        assertTrue(r.isValid(), () -> "a query declaring nothing must not make this a GraphQL endpoint, got: " + r.errors());
    }

    @Test void malformedRateLimit_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-bad-rate-limit.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("default_rate_limit")),
            () -> "expected a default_rate_limit error, got: " + r.errors());
    }

    // C4: a typo'd incremental.mode used to silently degrade to a full re-fetch forever;
    // watermark without param silently never applied the watermark.
    @Test void incrementalUnknownMode_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-incremental-bad-mode.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("incremental.mode")),
            () -> "expected an incremental.mode error, got: " + r.errors());
    }

    @Test void incrementalWatermarkWithoutParam_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-incremental-no-param.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("incremental.param")),
            () -> "expected an incremental.param error, got: " + r.errors());
    }

    @Test void incrementalNonObject_isError() throws Exception {
        // The runtime casts incremental to a Map — a scalar passed validate and CCE'd at run.
        // The declaration owns the shape since #1438: one refusal, still teaching the block.
        ValidationResult r = service.validate(load("rest-incremental-not-object.yaml"));
        assertFalse(r.isValid());
        List<ValidationError> refusals = r.errors().stream().filter(e -> e.path().endsWith(".incremental")).toList();
        assertEquals(1, refusals.size(), () -> "one defect, one refusal: " + r.errors());
        assertTrue(String.join(" ", refusals.get(0).recommendations()).contains("block"),
            () -> "expected the refusal to teach the block shape, got: " + refusals);
    }

    @Test void incrementalApiCursor_warnsButStaysValid() throws Exception {
        // api_cursor is documented as deferred-treated-as-full: accept with a WARNING.
        ValidationResult r = service.validate(load("rest-incremental-api-cursor.yaml"));
        assertTrue(r.isValid(), () -> "api_cursor must stay valid, got: " + r.errors());
        assertTrue(r.warnings().stream().anyMatch(w ->
                w.path().contains("incremental.mode") && w.message().contains("full")),
            () -> "expected a treated-as-full warning, got: " + r.warnings());
    }

    @Test void incrementalWatermarkWithoutWatermarkPath_isError() throws Exception {
        // Without watermark_path the watermark never advances: full re-fetches forever.
        ValidationResult r = service.validate(load("rest-incremental-no-wpath.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("incremental.watermark_path")),
            () -> "expected a watermark_path error, got: " + r.errors());
    }

    // Finding 5 (validator == runtime): RestEndpointWriter.parseHybridSpec refuses a target.table
    // that is not a plain identifier / schema.table pair — before this fix, the validator did not
    // mirror that check, so `lm apply` accepted a manifest that only failed mid-run.
    @Test void targetTableContainingASingleQuote_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-bad-table.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("target.table")),
            () -> "expected a target.table error, got: " + r.errors());
    }

    @Test void validManifest_passes() throws Exception {
        ValidationResult r = service.validate(load("rest-valid.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        // The anti-lie guard of the closed root: every connector-level key this fixture carries —
        // schedule, window, headers, auth — is one the door really reads.
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r),
            () -> "no honored key may be called unknown: " + r.warnings());
    }

    /**
     * The root was the unswept tier: `base_urls`, `headerz` and `windo` were accepted and
     * persisted while a typo inside `auth` — a closed block — was named. Closing it took five
     * declarations, because the door reads five keys this ConfigDef did not list: `auth`,
     * `headers`, `defaults`, `graphql_path` and `window`. The manifest stays VALID.
     */
    @Test void misspelledRootKeys_areNamed() throws Exception {
        ValidationResult r = service.validate(load("rest-unknown-root-keys.yaml"));
        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.base_urls", "spec.headerz", "spec.windo"),
            ValidationWarnings.unknownKeyPaths(r),
            () -> "all three root typos must be named, got warnings: " + r.warnings());
    }

    /** `default_schedule` schedules nothing — `schedule` does — so a manifest carrying it hears so, like any typo. */
    @Test void defaultSchedule_isNamedUnknown() throws Exception {
        JsonNode doc = mapper.readTree(REST_HEAD.formatted("{\"id\":\"e\",\"method\":\"GET\",\"path\":\"/x\"," + EP_TAIL + "}"));
        ((ObjectNode) doc.path("spec")).put("default_schedule", "0 6 * * *");

        ValidationResult r = service.validate(doc);

        assertTrue(r.isValid(), () -> "an unknown key is a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.default_schedule"), ValidationWarnings.unknownKeyPaths(r),
            () -> "named by its path, got: " + r.warnings());
    }

    /**
     * The type's descriptor (`META-INF/connectors/rest-generic/config-schema.json`) may not offer a key the
     * validator calls unknown: it carried `default_schedule` from the start, read by nothing. `kind` is exempt —
     * it is the stored config's discriminator, written by the apply door from `metadata.connector_type`.
     */
    @Test void theTypeDescriptorOffersNoKeyTheValidatorCallsUnknown() throws Exception {
        JsonNode schema;
        try (InputStream is = getClass().getResourceAsStream("/META-INF/connectors/rest-generic/config-schema.json")) {
            schema = mapper.readTree(is);
        }
        ConfigDef def = new RestConnectorValidator().configDef();
        List<String> undeclared = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(key -> {
            if (!"kind".equals(key) && !def.declares(key)) undeclared.add(key);
        });

        assertEquals(List.of(), undeclared, "descriptor keys the validator does not declare");
    }

    /** Same family as headers: the declaration owns the shape, the cross-field rule the value. */
    @Test void rateLimitThatIsNotAString_isRefusedExactlyOnce() throws Exception {
        ValidationResult r = service.validate(load("rest-rate-limit-not-a-string.yaml"));
        assertFalse(r.isValid());
        assertEquals(1, r.errors().stream()
                .filter(e -> e.path().equals("spec.default_rate_limit")).count(),
            () -> "one defect, one refusal: " + r.errors());
    }

    /** `graphql_path` is read by the apply handler, so a graphql manifest must not be accused. */
    @Test void graphqlManifest_carriesNoUnknownKey() throws Exception {
        ValidationResult r = service.validate(load("rest-graphql-non-relay.yaml"));
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r),
            () -> "graphql_path is load-bearing at apply: " + r.warnings());
    }

    /**
     * The 500 the declaration closes, at this tier; the door's own witness is
     * {@code ConnectorApplyValidationTest}, where it was measured. `validateAuth` cannot catch
     * this one — it returns early on a non-object — so the type check had to come from declaring
     * the block.
     */
    @Test void authThatIsNotABlock_isRefusedInsteadOfBreakingTheApplyDoor() throws Exception {
        ValidationResult r = service.validate(load("rest-auth-not-object.yaml"));
        assertFalse(r.isValid(), "a string where a block belongs must not reach the handler");
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.auth")),
            () -> "the refusal must name spec.auth, got: " + r.errors());
    }

    @Test void missingTokenEnv_whenBearer_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-bearer-no-token.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.auth.token_env")));
    }

    @Test void bearer_explicitNullTokenEnv_isError() throws Exception {
        // token_env: null — NullNode.asText() is "null"; bearer must still require a usable token.
        ValidationResult r = service.validate(load("rest-bearer-null-token.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().equals("spec.auth.token_env") && e.message().contains("bearer")),
            () -> "expected bearer null token_env rejected, got: " + r.errors());
    }

    /**
     * The sibling of jdbc's inline {@code password}, and the reason the ticket's "the only such
     * field across all manifest kinds" was wrong (#530): {@code token_value} is a literal bearer
     * token, declared dev-only, consumed as-is by {@code BearerAuthProviderFactory}. The hazard is
     * not precedence — {@code token_env} is read first, so nothing is silently overridden — it is a
     * secret in a file the integrator is told to commit. A warning: the field stays usable.
     */
    @Test void inlineTokenValue_isAdvisedAgainst() throws Exception {
        ValidationResult r = service.validate(load("rest-inline-token-value.yaml"));
        assertTrue(r.isValid(), () -> "a documented dev/test affordance is not refused: " + r.errors());

        List<ValidationError> advisories = r.warnings().stream()
            .filter(w -> w.path().equals("spec.auth.token_value")).toList();
        assertEquals(1, advisories.size(), () -> "one field, one advisory: " + r.warnings());
        assertFalse(advisories.get(0).recommendations().isEmpty(),
            "an advisory an integrator can act on carries the line to write instead");
    }

    /**
     * …and only under {@code kind: bearer}, where the sentence is true. {@code token_value} is
     * declared optional for every kind and nothing but the bearer factory reads it, so warning
     * about a "literal bearer token" on a basic-auth manifest would name something that is not
     * there. A secret in a key the runtime ignores is a different finding; the CI grep the docs
     * give bans the line whatever the kind (Copilot round 2).
     */
    @Test void tokenValue_underAnotherKind_drawsNoBearerAdvisory() throws Exception {
        ValidationResult r = service.validate(load("rest-token-value-not-bearer.yaml"));
        assertEquals(List.of(), r.warnings().stream()
                .filter(w -> w.path().equals("spec.auth.token_value")).toList(),
            () -> "the advisory speaks only where its wording holds: " + r.warnings());
    }

    /**
     * Copilot on #1394: the oauth2 keys are declared for every kind in the closed block, so under {@code kind: bearer}
     * they validated silently — a manifest whose {@code kind} says one thing and whose keys say another.
     */
    @Test void oauth2Keys_underAnotherKind_areNamedAsIgnored() throws Exception {
        ValidationResult r = service.validate(mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"mixed-auth","connector_type":"rest-generic","scopes":["test"]},
             "spec":{"base_url":"https://api.example.com",
                     "auth":{"kind":"bearer","token_env":"API_TOKEN","scope":"orders.read","client_auth":"post"},
                     "endpoints":[{"id":"contacts","method":"GET","path":"/v1/contacts",%s}]}}""".formatted(EP_TAIL)));
        assertTrue(r.isValid(), () -> "a warning, not a refusal: " + r.errors());
        assertEquals(List.of("spec.auth.client_auth", "spec.auth.scope"), r.warnings().stream()
                .map(ValidationError::path).filter(p -> p.startsWith("spec.auth.")).sorted().toList(),
            () -> "each oauth2 key named: " + r.warnings());
    }

    private ValidationResult withAuth(String auth) throws Exception {
        return service.validate(mapper.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"auth-check","connector_type":"rest-generic","scopes":["test"]},
             "spec":{"base_url":"https://api.example.com","auth":%s,
                     "endpoints":[{"id":"contacts","method":"GET","path":"/v1/contacts",%s}]}}""".formatted(auth, EP_TAIL)));
    }

    /** Copilot on #1394: client_auth is checked where it means something — under oauth2 — and only warned about elsewhere. */
    @Test void clientAuth_isBasicOrPost_underOAuth2_andOnlyWarnedAboutElsewhere() throws Exception {
        ValidationResult oauth2 = withAuth("""
            {"kind":"oauth2","token_url":"https://a.example/t","client_id":"c","client_secret_env":"S","client_auth":"jwt"}""");
        assertTrue(oauth2.errors().stream().anyMatch(e -> e.path().equals("spec.auth.client_auth")),
            () -> "expected client_auth refused under oauth2, got: " + oauth2.errors());
        ValidationResult bearer = withAuth("""
            {"kind":"bearer","token_env":"API_TOKEN","client_auth":"jwt"}""");
        assertTrue(bearer.isValid(), () -> "a key the runtime ignores is not refused: " + bearer.errors());
        assertTrue(bearer.warnings().stream().anyMatch(w -> w.path().equals("spec.auth.client_auth")),
            () -> "but it is named: " + bearer.warnings());
    }

    /** #1397: a lookback is a duration the runtime can read, or apply refuses it. */
    @Test void incrementalLookback_mustBeADuration() throws Exception {
        String head = "{\"apiVersion\":\"connectors.lumnik.io/v1\",\"kind\":\"Connector\","
            + "\"metadata\":{\"name\":\"lookback\",\"connector_type\":\"rest-generic\",\"scopes\":[\"test\"]},"
            + "\"spec\":{\"base_url\":\"https://api.example.com\",\"endpoints\":[{\"id\":\"contacts\",\"method\":\"GET\","
            + "\"path\":\"/v1/contacts\",\"incremental\":{\"mode\":\"watermark\",\"param\":\"since\","
            + "\"watermark_path\":\"$.updated_at\",\"lookback\":\"%s\"}," + EP_TAIL + "}]}}";
        assertTrue(service.validate(mapper.readTree(head.formatted("10m"))).isValid());
        ValidationResult r = service.validate(mapper.readTree(head.formatted("ten minutes")));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".incremental.lookback")),
            () -> "expected lookback refused, got: " + r.errors());
    }

    /** A manifest that names its secret hears nothing about it. */
    @Test void tokenEnvAlone_drawsNoAdvisory() throws Exception {
        ValidationResult r = service.validate(load("rest-valid.yaml"));
        assertEquals(List.of(), r.warnings().stream()
                .filter(w -> w.path().startsWith("spec.auth")).toList(),
            () -> "nothing to advise when no literal is present: " + r.warnings());
    }

    @Test void cursorPagination_withoutCursorPath_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-cursor-no-field.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("cursor_path")));
    }

    @Test void duplicateEndpointIds_areError() throws Exception {
        ValidationResult r = service.validate(load("rest-duplicate-endpoints.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("duplicate endpoint id")));
    }

    @Test void malformedPromote_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-bad-promote.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("promote") && e.message().contains("frobnicate")),
            () -> "expected an unknown-type promote error, got: " + r.errors());
    }

    // Registry case: promote[].transform was read by RestEndpointWriter (Transform.valueOf →
    // run-time crash on a typo) but never validated at apply.
    @Test void promoteTransform_known_isValid() throws Exception {
        ValidationResult r = service.validate(load("rest-promote-transform-ok.yaml"));
        assertTrue(r.isValid(), () -> "to_upper is a real transform, got: " + r.errors());
    }

    // validator == runtime, both ways (Copilot, PR #172): the writer's `transformRaw != null`
    // guard treats an explicit null as absent — but Transform.valueOf("") crashes on a blank.
    @Test void promoteTransform_explicitNull_isValid() throws Exception {
        ValidationResult r = service.validate(load("rest-promote-transform-null.yaml"));
        assertTrue(r.isValid(), () -> "transform: null = absent at runtime, got: " + r.errors());
    }

    @Test void promoteTransform_blank_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-promote-transform-blank.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".transform")),
            () -> "a blank transform crashes the run (valueOf(\"\")), got: " + r.errors());
    }

    @Test void promoteTransform_unknown_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-promote-bad-transform.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("promote") && e.path().endsWith(".transform")
            && e.message().contains("shout") && e.message().contains("to_upper")),
            () -> "expected an unknown-transform error naming the allowed set, got: " + r.errors());
    }

    @Test void pagePagination_withoutPageParam_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-page-no-param.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("pagination.page_param")),
            () -> "expected a missing page_param error, got: " + r.errors());
    }

    @Test void cursorPagination_withoutHasMorePath_isError() throws Exception {
        // without has_more_path, next() stops after page 1 → silent partial ingestion
        ValidationResult r = service.validate(load("rest-cursor-no-hasmore.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("pagination.has_more_path")),
            () -> "expected a missing has_more_path error, got: " + r.errors());
    }

    @Test void linkHeaderPagination_withoutResponsePath_isError() throws Exception {
        // link-header items() defaults response_path to "$" → wrong shape; require it explicitly
        ValidationResult r = service.validate(load("rest-linkheader-no-response.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("pagination.response_path")),
            () -> "expected a missing response_path error, got: " + r.errors());
    }

    @Test void customPagination_isRefused() throws Exception {
        // custom has no runtime: it passed validate, then the run failed on page 2 (#1245)
        ValidationResult r = service.validate(load("rest-custom-pagination.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith("pagination.kind")
                && e.message().contains("custom pagination is not supported")),
            () -> "expected a refusal of kind: custom, got: " + r.errors());
    }

    @Test void unknownConnectorKind_isError() throws Exception {
        ValidationResult r = service.validate(load("unknown-kind.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("metadata.connector_type")));
    }

    @Test void graphql_endpoint_requires_query_not_path() throws Exception {
        ValidationResult r = service.validate(load("rest-graphql-no-query.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".query")),
            () -> "expected a missing-query error, got: " + r.errors());
        assertTrue(r.errors().stream().noneMatch(e -> e.path().endsWith(".path") || e.path().endsWith(".method")),
            () -> "graphql endpoint should not require path/method, got: " + r.errors());
    }

    @Test void relay_variable_binding_requires_cursor_var() throws Exception {
        ValidationResult r = service.validate(load("rest-graphql-relay-no-cursor-var.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".cursor_var")),
            () -> "expected a missing cursor_var error, got: " + r.errors());
    }

    @Test void header_requires_name_and_exactly_one_value() throws Exception {
        ValidationResult r = service.validate(load("rest-header-no-name.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().startsWith("spec.headers")),
            () -> "expected a header validation error, got: " + r.errors());
    }

    @Test void header_explicitNullValue_isError() throws Exception {
        // { name: X, value: null } passes a naive isMissingNode() XOR but the runtime skips it.
        ValidationResult r = service.validate(load("rest-header-null-value.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().startsWith("spec.headers")),
            () -> "expected a header validation error for explicit null value, got: " + r.errors());
    }

    @Test void header_explicitNullName_isError() throws Exception {
        // { name: null } — NullNode.asText() is "null"; runtime skips it (Map.get("name")==null).
        ValidationResult r = service.validate(load("rest-header-null-name.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.headers[0].name")),
            () -> "expected explicit-null header name rejected, got: " + r.errors());
    }

    @Test void header_blankValueEnv_isError() throws Exception {
        // { name: X, value_env: "" } resolves to nothing at runtime — reject it.
        ValidationResult r = service.validate(load("rest-header-blank-env.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().startsWith("spec.headers")),
            () -> "expected a header validation error for blank value_env, got: " + r.errors());
    }

    @Test void cursorPagination_withVariableBinding_isError() throws Exception {
        // CursorPaginationStrategy throws on cursor_binding=variable — only relay supports it.
        ValidationResult r = service.validate(load("rest-cursor-variable-binding.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".cursor_binding")),
            () -> "expected cursor_binding=variable rejected for cursor kind, got: " + r.errors());
    }

    @Test void requiredKey_explicitNull_isError() throws Exception {
        // cursor_var: null — NullNode.asText() is "null" (non-blank); the runtime drops nulls in
        // PaginationFactory, so it must be rejected as missing, not treated as present.
        ValidationResult r = service.validate(load("rest-graphql-null-cursor-var.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".cursor_var")),
            () -> "expected cursor_var=null rejected, got: " + r.errors());
    }

    @Test void promoteEntry_explicitNullName_isError() throws Exception {
        // Same null-as-"null" trap as requireKeys: a null promote field must be rejected.
        ValidationResult r = service.validate(load("rest-promote-null-name.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("promote") && e.path().endsWith(".name")),
            () -> "expected promote name=null rejected, got: " + r.errors());
    }

    @Test void nonGraphql_variableBinding_isError() throws Exception {
        // variable binding writes the cursor into a POST body; a non-graphql endpoint emits a GET,
        // so the cursor is lost and the relay loop refetches page 1 up to MAX_PAGES.
        ValidationResult r = service.validate(load("rest-relay-variable-non-graphql.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().endsWith(".cursor_binding") && e.message().contains("graphql")),
            () -> "expected variable binding rejected for non-graphql endpoint, got: " + r.errors());
    }

    @Test void endpointLevelHeaders_isError() throws Exception {
        // E1 ships connector-level headers only; the shallow merge would let an endpoint silently
        // override connector headers, so per-endpoint headers must be rejected.
        ValidationResult r = service.validate(load("rest-endpoint-headers.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().equals("spec.endpoints[0].headers")),
            () -> "expected per-endpoint headers rejected, got: " + r.errors());
        assertEquals(List.of(), ValidationWarnings.unknownKeyPaths(r), "refused once, not also warned as unknown (#1438)");
    }

    @Test void graphql_blankQuery_isError() throws Exception {
        // query: "   " is present + a string, so a presence check passes; it must be rejected as blank.
        ValidationResult r = service.validate(load("rest-graphql-blank-query.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".query")),
            () -> "expected blank query rejected, got: " + r.errors());
    }

    @Test void graphql_nullQuery_isError() throws Exception {
        // query: null — the recurring NullNode.asText()=="null" trap; must still be rejected.
        ValidationResult r = service.validate(load("rest-graphql-null-query.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".query")),
            () -> "expected null query rejected, got: " + r.errors());
    }

    @Test void graphql_endpoint_requires_relay_variable_pagination() throws Exception {
        ValidationResult r = service.validate(load("rest-graphql-non-relay.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("pagination") && e.message().contains("relay")),
            () -> "expected a relay-pagination-required error, got: " + r.errors());
    }

    @Test void bodyLink_requires_linkPath() throws Exception {
        ValidationResult r = service.validate(load("rest-bodylink-no-linkpath.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.path().endsWith(".link_path")),
            () -> "expected link_path required for body-link, got: " + r.errors());
    }

    @Test void preset_springPage_validates() throws Exception {
        ValidationResult r = service.validate(load("rest-preset-spring-page.yaml"));
        assertTrue(r.isValid(), () -> "expected spring-page preset valid, got: " + r.errors());
    }

    @Test void preset_hateoas_withoutItems_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-preset-hateoas-no-items.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("pagination") && e.message().contains("items")),
            () -> "expected missing-items error, got: " + r.errors());
    }

    @Test void preset_hateoas_badItems_isError() throws Exception {
        // items is interpolated into $._embedded.{items}; a value with JSONPath metacharacters must
        // be rejected rather than producing a surprising/invalid response_path.
        ValidationResult r = service.validate(load("rest-preset-hateoas-bad-items.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().endsWith(".items") && e.message().contains("identifier")),
            () -> "expected items-must-be-identifier error, got: " + r.errors());
    }

    /**
     * The generated draft has to survive the WHOLE validation path, not just the envelope: the
     * REST ConfigDef and validateCrossField run after it, and they need CDI (the pagination preset
     * registry), so a plain unit test cannot see them. `lm source rest from-openapi --apply` posts
     * straight here, so this is the only assertion that earns the claim "the import applies".
     */
    @Test void generatedManifest_passesTheWholeValidationPath() {
        String spec = """
            openapi: 3.0.0
            info: { title: Demo API, version: 1.0 }
            servers: [{ url: https://api.demo.io }]
            paths:
              /v1/things:
                get:
                  parameters: [{ name: page, in: query, schema: { type: integer, default: 0 } }]
                  responses:
                    "200":
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              content: { type: array, items: { type: object } }
            """;
        String manifest = new OpenApiImporter().importFromYaml(spec, "demo", List.of("billing"));
        JsonNode doc = mapper.valueToTree(new Yaml().load(manifest));
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "the hub rejects its own generated manifest: " + r.errors());
        assertFalse(doc.path("spec").path("endpoints").isEmpty(), () -> "no endpoint was drafted:\n" + manifest);
    }

    /**
     * The draft of the shipped demo API's /openapi.json (docs/connectors/rest/demo/acme-api.py,
     * saved as served) — three paths, three auth schemes, three paging styles — passes what
     * `lm validate` runs, with nothing to warn about: what the spec does not say is a comment,
     * not a key the validator would have to question.
     */
    @Test void theDemoSpecDraft_passesTheWholeValidationPath_withoutWarnings() throws Exception {
        String spec;
        try (InputStream is = getClass().getResourceAsStream("/openapi/acme-demo-openapi.json")) {
            spec = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String manifest = new OpenApiImporter().importFromYaml(spec, "acme-from-openapi", List.of("billing"));
        JsonNode doc = mapper.valueToTree(new Yaml().load(manifest));
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "the hub rejects its own draft of the demo spec: " + r.errors() + "\n" + manifest);
        assertTrue(r.warnings().isEmpty(), () -> "the draft of the demo spec draws warnings: " + r.warnings() + "\n" + manifest);
    }
    /**
     * Declaring `headers` as a LIST gave the runner the shape check that `validateHeaders` used to
     * do by hand — so the hand check had to stop, or one mistake would be reported twice. Counted,
     * because that is the only way a duplicate shows.
     */
    @Test void headersThatIsNotAList_isRefusedExactlyOnce() throws Exception {
        ValidationResult r = service.validate(load("rest-headers-not-a-list.yaml"));
        assertFalse(r.isValid());
        assertEquals(1, r.errors().stream().filter(e -> e.path().equals("spec.headers")).count(),
            () -> "one defect, one refusal: " + r.errors());
    }
}
