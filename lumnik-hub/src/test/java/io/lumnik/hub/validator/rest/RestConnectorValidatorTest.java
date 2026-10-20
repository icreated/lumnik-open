package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.discovery.OpenApiImporter;
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
        ValidationResult r = service.validate(load("rest-incremental-not-object.yaml"));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> e.path().contains("incremental")
                && e.message().contains("block")),
            () -> "expected a non-object incremental error, got: " + r.errors());
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
                  responses: { "200": { description: ok } }
            """;
        String manifest = new OpenApiImporter().importFromYaml(spec, "demo", List.of("billing"));
        JsonNode doc = mapper.valueToTree(new Yaml().load(manifest));
        ValidationResult r = service.validate(doc);
        assertTrue(r.isValid(), () -> "the hub rejects its own generated manifest: " + r.errors());
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
