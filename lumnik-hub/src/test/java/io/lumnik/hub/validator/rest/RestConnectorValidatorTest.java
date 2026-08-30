package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.discovery.OpenApiImporter;
import io.lumnik.hub.validator.ConnectorValidationService;
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

    @Test void cursorPagination_withoutCursorPath_isError() throws Exception {
        ValidationResult r = service.validate(load("rest-cursor-no-field.yaml"));
        assertTrue(r.errors().stream().anyMatch(e ->
            e.path().contains("cursor_path")));
    }

    @Test void duplicateEndpointIds_areError() throws Exception {
        ValidationResult r = service.validate(load("rest-duplicate-endpoints.yaml"));
        assertTrue(r.errors().stream().anyMatch(e -> e.message().contains("duplicate endpoint id")));
    }

    @Test void noTags_isWarningNotError() throws Exception {
        ValidationResult r = service.validate(load("rest-no-tags.yaml"));
        assertTrue(r.isValid(), () -> "expected valid, got: " + r.errors());
        assertTrue(r.warnings().stream().anyMatch(w -> w.path().equals("spec.endpoints[0].target.tags")));
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
}
