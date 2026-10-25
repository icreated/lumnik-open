package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.EnvelopeValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.rest.EndpointsBlockValidator;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiImporterTest {

    /**
     * What a spec URL that is not a spec must do. Measured before it was written down: the parser
     * does not answer null for an unreachable or non-spec document — it raises a
     * NullPointerException from inside itself. Escaping the importer, that reached the operator as
     * {@code Internal Server Error} plus an error id, with the URL they mistyped nowhere in sight.
     *
     * <p>Named here rather than dressed at the resource, because what the failure MEANS is this
     * class's knowledge: a blanket catch one layer up would have to report a genuine defect in our
     * own manifest build as the customer's network.
     */
    @Test void a_url_that_holds_no_spec_is_refused_by_name() {
        var refused = assertThrows(ConnectorException.class,
            () -> new OpenApiImporter().importFromUrl("http://localhost:1/no-such-spec", "nope", List.of()));

        assertTrue(refused.getMessage().contains("http://localhost:1/no-such-spec"), refused.getMessage());
    }

    /**
     * The refusal names the URL through {@link io.lumnik.hub.connector.rest.Urls#forDisplay}: a
     * spec URL legally carries userinfo, and this message reaches a 502 body and a hub log. The
     * rule itself is pinned in {@code UrlsTest}; what is asserted here is that this door uses it.
     */
    @Test void a_refused_spec_url_is_shown_without_its_credentials() {
        var refused = assertThrows(ConnectorException.class,
            () -> new OpenApiImporter().importFromUrl(
                "http://user:s3cr3t@localhost:1/no-such-spec?token=abc", "nope", List.of()));

        assertFalse(refused.getMessage().contains("s3cr3t"), refused.getMessage());
        assertFalse(refused.getMessage().contains("abc"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no-such-spec"), refused.getMessage());
    }

    @Test void parses_minimal_openapi() {
        String yaml = """
            openapi: 3.0.0
            info: { title: Demo API, version: 1.0 }
            servers: [{ url: https://api.demo.io }]
            paths:
              /v1/things:
                get:
                  parameters: [{ name: starting_after, in: query, schema: { type: string } }]
                  responses:
                    "200":
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              has_more: { type: boolean }
                              data: { type: array, items: { type: object, properties: { id: { type: string } } } }
            """;
        String manifest = new OpenApiImporter().importFromYaml(yaml, "demo", List.of("test"));
        assertTrue(manifest.contains("name: demo"), manifest);
        assertTrue(manifest.contains("kind: cursor"), manifest);
        assertTrue(manifest.contains("path: \"/v1/things\""), manifest);
        // The import writes the scope where the validator reads it — and nowhere else.
        assertTrue(manifest.contains("  scopes: [test]"), manifest);
        assertFalse(manifest.contains("tags:"), manifest);
    }

    /**
     * "Mal d'Intelligences" — the pathological model/path names an OpenAPI import meets in the wild:
     *   - DDD bounded-context collisions that Swagger numbers as Person_1 / Person_2: two distinct
     *     paths that slug to the SAME identifier (here /order-items and /order_items → "order_items").
     *   - "user-friendly" renames carrying accents / apostrophes / CJK: a name that the slug regex
     *     collapses to the EMPTY string (here /日本語), yielding a blank endpoint name + "ext.demo_".
     *
     * Desired invariant: every generated endpoint has a non-empty name, and names + target tables are
     * unique. RED today: the importer slugs each path independently with no de-duplication and no
     * empty-fallback, so it silently emits duplicate/blank identifiers that break at apply time.
     */
    @Test void pathological_paths_yield_unique_nonempty_identifiers() {
        String yaml = """
            openapi: 3.0.0
            info: { title: Messy API, version: 1.0 }
            servers: [{ url: https://api.messy.io }]
            paths:
              /order-items:
                get: %1$s
              /order_items:
                get: %1$s
              /日本語:
                get: %1$s
            """.formatted(PAGED_GET);
        String manifest = new OpenApiImporter().importFromYaml(yaml, "demo", List.of("test"));
        System.out.println("=== generated manifest ===\n" + manifest);

        List<String> names = manifest.lines()
            .filter(l -> l.trim().startsWith("- id:"))
            .map(l -> l.substring(l.indexOf(':') + 1).trim())
            .toList();
        List<String> tables = manifest.lines()
            .filter(l -> l.contains("table: ext."))
            .map(l -> l.replaceAll(".*table: (ext\\.[^,]*).*", "$1").trim())
            .toList();

        // assertAll so all three pathologies surface in one run (don't stop at the first).
        assertAll(
            () -> assertFalse(names.contains(""),
                "an endpoint got a blank name (non-ASCII path collapsed to empty)"),
            () -> assertEquals(names.size(), Set.copyOf(names).size(),
                "duplicate endpoint names — DDD-style collision not disambiguated: " + names),
            () -> assertEquals(tables.size(), Set.copyOf(tables).size(),
                "duplicate target tables — two endpoints would write the same ext.* table: " + tables));
    }

    /**
     * The connector {@code name} is user-provided (RestDiscoveryResource#fromOpenApi) and forms the
     * {@code ext.<name>_<slug>} table prefix. Spaces / unicode / punctuation in the name must not leak
     * into the SQL identifier even though the path slug is already safe.
     */
    @Test void user_provided_name_is_sanitized_into_the_table_identifier() {
        String yaml = """
            openapi: 3.0.0
            info: { title: X, version: 1.0 }
            servers: [{ url: https://api.x.io }]
            paths:
              /things: { get: %s }
            """.formatted(PAGED_GET);
        String manifest = new OpenApiImporter().importFromYaml(yaml, "Mon API Café!", List.of());

        manifest.lines()
            .filter(l -> l.contains("table: ext."))
            .map(l -> l.replaceAll(".*table: (ext\\.[^,]*).*", "$1").trim())
            .forEach(table -> assertTrue(table.matches("ext\\.[a-z0-9_]+"),
                "table is not a safe SQL identifier: " + table));
    }

    // ---------------------------------------------------------------------------------------
    // The importer's output has to survive the hub's OWN validators, not merely look right.
    // Every assertion above this line tests the emitted STRING — which is why a manifest that
    // no tenant could ever apply passed a green suite for months. These run the real
    // EnvelopeValidator and the real per-endpoint ConfigDef over the parsed document.
    // ---------------------------------------------------------------------------------------

    /** The same shape `lm source rest from-openapi` hands to `POST /api/platform/connectors/apply`. */
    private JsonNode generated() {
        String yaml = """
            openapi: 3.0.0
            info: { title: Demo API, version: 1.0 }
            servers: [{ url: https://api.demo.io }]
            paths:
              /v1/things:
                get: %s
            """.formatted(PAGED_GET);
        String manifest = new OpenApiImporter().importFromYaml(yaml, "demo", List.of("billing"));
        try {
            return new YAMLMapper().readTree(manifest);
        } catch (Exception e) {
            throw new AssertionError("the importer emitted YAML the hub cannot parse:\n" + manifest, e);
        }
    }

    @Test void generated_manifest_satisfies_the_hubs_envelope_validator() {
        List<ValidationError> errs = EnvelopeValidator.validate(generated());
        assertTrue(errs.isEmpty(), () -> "the hub rejects its own generated manifest: " + errs);
    }

    @Test void generated_endpoints_satisfy_the_per_endpoint_config_def() {
        JsonNode endpoints = generated().path("spec").path("endpoints");
        assertTrue(endpoints.isArray() && !endpoints.isEmpty(), "no endpoints were generated");
        endpoints.forEach(ep -> {
            List<ValidationError> errs =
                ConfigDefRunner.run(EndpointsBlockValidator.perEndpointDef(), ep, "spec.endpoints[]");
            assertTrue(errs.isEmpty(), () -> "the hub rejects a generated endpoint: " + errs);
        });
    }

    @Test void generated_manifest_does_not_pin_a_tenant() {
        // metadata.tenant may be declared, but apply rejects it when it does not match the
        // authenticated tenant — so a hardcoded value makes the draft un-appliable for everyone else.
        assertFalse(generated().path("metadata").has("tenant"),
            "the generated manifest pins metadata.tenant, so only that tenant can apply it");
    }

    @Test void endpoint_ids_are_kebab_even_when_the_path_starts_with_a_digit() {
        // Identifiers.slug is a SQL-identifier sanitiser: it emits underscores and may lead with
        // one (IdentifiersTest pins slug("/1/things") == "_1_things"). The endpoint id lives under
        // a different contract — ^[a-z][a-z0-9-]*$ — so it needs its own derivation, not the table's.
        String yaml = """
            openapi: 3.0.0
            info: { title: Demo API, version: 1.0 }
            servers: [{ url: https://api.demo.io }]
            paths:
              /1/things:
                get: %s
            """.formatted(PAGED_GET);
        JsonNode doc;
        try {
            doc = new YAMLMapper().readTree(new OpenApiImporter().importFromYaml(yaml, "demo", List.of()));
        } catch (Exception e) { throw new AssertionError(e); }
        doc.path("spec").path("endpoints").forEach(ep -> {
            List<ValidationError> errs =
                ConfigDefRunner.run(EndpointsBlockValidator.perEndpointDef(), ep, "spec.endpoints[]");
            assertTrue(errs.isEmpty(), () -> "digit-leading path yields an invalid endpoint id: " + errs);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Auth and paging follow what the spec SAYS. A bearer block on a basic-auth path fails the
    // first run; a page draft that starts at 1 on a source numbered from 0 loses a page and
    // reports success. What the spec does not say is left out, with a comment naming what to
    // declare — never guessed.
    // ---------------------------------------------------------------------------------------

    /** A GET whose paging the draft can read whole: page numbers from 0, a size, one array of rows with an id. */
    private static final String PAGED_GET = "{ parameters: " + params("page", "default: 0", "size", "default: 50")
        + ", responses: " + rowsIn("content") + " }";

    /** Query parameters as {@code name, schema-extras} pairs, e.g. {@code params("page", "default: 0")}. */
    private static String params(String... nameAndSchema) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nameAndSchema.length; i += 2) {
            if (i > 0) sb.append(", ");
            String extra = nameAndSchema[i + 1].isEmpty() ? "" : ", " + nameAndSchema[i + 1];
            sb.append("{ name: ").append(nameAndSchema[i]).append(", in: query, schema: { type: ")
              .append("starting_after".equals(nameAndSchema[i]) ? "string" : "integer").append(extra).append(" } }");
        }
        return sb.append(']').toString();
    }

    /** A 200 whose body is an object; {@code rows} holds an array of objects with an id, plus any extra properties. */
    private static String rowsIn(String rows, String... extraProps) {
        String extra = extraProps.length == 0 ? "" : ", " + String.join(", ", extraProps);
        return "{ \"200\": { description: ok, content: { application/json: { schema: { type: object, properties: { "
            + rows + ": { type: array, items: { type: object, properties: { id: { type: integer } } } }" + extra
            + " } } } } } }";
    }

    private static final String SCHEMES = """
        components:
          securitySchemes:
            basic: { type: http, scheme: basic }
            bearer: { type: http, scheme: bearer }
            hkey: { type: apiKey, in: header, name: X-API-Key }
            qkey: { type: apiKey, in: query, name: api_key }
            oauth: { type: oauth2, flows: { clientCredentials: { tokenUrl: "https://auth.demo.io/token", scopes: {} } } }
        """;

    /** A spec over {@link #SCHEMES}; {@code paths} is the YAML under {@code paths:}, indented two spaces. */
    private static String spec(String globalSecurity, String paths) {
        return "openapi: 3.0.3\ninfo: { title: Demo, version: '1' }\nservers: [{ url: https://api.demo.io }]\n"
            + SCHEMES + (globalSecurity == null ? "" : "security: " + globalSecurity + "\n") + "paths:\n" + paths;
    }

    /** One GET path; {@code operation} is the operation's flow mapping. */
    private static String get(String path, String operation) {
        return "  " + path + ":\n    get: " + operation + "\n";
    }

    /** {@link #PAGED_GET} with a security requirement of its own. */
    private static String pagedGet(String security) {
        return PAGED_GET.substring(0, PAGED_GET.length() - 1) + ", security: " + security + " }";
    }

    private static String draft(String spec) {
        return new OpenApiImporter().importFromYaml(spec, "demo", List.of());
    }

    private static JsonNode parse(String manifest) {
        try {
            return new YAMLMapper().readTree(manifest);
        } catch (Exception e) {
            throw new AssertionError("the importer emitted YAML the hub cannot parse:\n" + manifest, e);
        }
    }

    private static JsonNode auth(String spec) {
        return parse(draft(spec)).path("spec").path("auth");
    }

    private static JsonNode onlyEndpoint(String manifest) {
        JsonNode endpoints = parse(manifest).path("spec").path("endpoints");
        assertEquals(1, endpoints.size(), () -> "expected one drafted endpoint:\n" + manifest);
        return endpoints.get(0);
    }

    @Test void http_basic_security_drafts_basic_auth_with_a_secret_name() {
        JsonNode auth = auth(spec(null, get("/things", pagedGet("[{ basic: [] }]"))));
        assertEquals("basic", auth.path("kind").asText(), auth::toString);
        assertFalse(auth.path("username").asText().isBlank(), auth::toString);
        assertEquals("DEMO_PASSWORD", auth.path("password_env").asText(), auth::toString);
    }

    @Test void http_bearer_security_drafts_bearer_auth() {
        JsonNode auth = auth(spec(null, get("/things", pagedGet("[{ bearer: [] }]"))));
        assertEquals("bearer", auth.path("kind").asText(), auth::toString);
        assertEquals("DEMO_API_KEY", auth.path("token_env").asText(), auth::toString);
    }

    /** Stripe's own spec offers basic OR bearer: the bearer token is drafted whole, basic would need a user name. */
    @Test void of_two_alternatives_the_one_drafted_whole_wins() {
        assertEquals("bearer", auth(spec("[{ basic: [] }, { bearer: [] }]", get("/things", PAGED_GET))).path("kind").asText());
        assertEquals("basic", auth(spec("[{ oauth: [] }, { basic: [] }]", get("/things", PAGED_GET))).path("kind").asText());
    }

    @Test void an_api_key_security_drafts_api_key_auth_where_the_spec_puts_it() {
        JsonNode header = auth(spec(null, get("/things", pagedGet("[{ hkey: [] }]"))));
        assertEquals("api-key", header.path("kind").asText(), header::toString);
        assertEquals("header", header.path("in").asText(), header::toString);
        assertEquals("X-API-Key", header.path("name").asText(), header::toString);
        assertEquals("DEMO_API_KEY", header.path("value_env").asText(), header::toString);

        JsonNode query = auth(spec(null, get("/things", pagedGet("[{ qkey: [] }]"))));
        assertEquals("query", query.path("in").asText(), query::toString);
        assertEquals("api_key", query.path("name").asText(), query::toString);
    }

    @Test void no_security_drafts_no_auth_block() {
        assertTrue(auth(spec(null, get("/things", PAGED_GET))).isMissingNode());
    }

    @Test void the_global_security_applies_and_an_operations_empty_list_lifts_it() {
        assertEquals("basic", auth(spec("[{ basic: [] }]", get("/things", PAGED_GET))).path("kind").asText());
        assertTrue(auth(spec("[{ basic: [] }]", get("/things", pagedGet("[]")))).isMissingNode());
    }

    @Test void a_scheme_the_draft_cannot_translate_is_left_out_and_named() {
        String manifest = draft(spec(null, get("/things", pagedGet("[{ oauth: [] }]"))));
        assertTrue(parse(manifest).path("spec").path("auth").isMissingNode(), manifest);
        assertTrue(manifest.contains("# auth: not drafted") && manifest.contains("oauth2"), manifest);
    }

    /** Auth is one per connector: the paths that ask for another credential are named, not silently given the wrong one. */
    @Test void paths_that_disagree_on_auth_keep_the_common_one_and_name_the_others() {
        String manifest = draft(spec(null,
            get("/a", pagedGet("[{ bearer: [] }]")) + get("/b", pagedGet("[{ bearer: [] }]"))
                + get("/c", pagedGet("[{ basic: [] }]"))));
        assertEquals("bearer", parse(manifest).path("spec").path("auth").path("kind").asText(), manifest);
        List<String> lines = manifest.lines().toList();
        int c = lines.indexOf("    - id: c");
        assertTrue(c > 0 && lines.get(c - 1).contains("# auth: this path asks for http basic"), manifest);
        assertEquals(1, manifest.lines().filter(l -> l.contains("# auth:")).count(), manifest);
    }

    @Test void page_numbers_are_drafted_from_where_the_spec_says_they_start() {
        JsonNode pagination = onlyEndpoint(draft(spec(null, get("/things", PAGED_GET)))).path("pagination");
        assertEquals("page", pagination.path("kind").asText(), pagination::toString);
        assertEquals("page", pagination.path("page_param").asText(), pagination::toString);
        assertEquals("size", pagination.path("size_param").asText(), pagination::toString);
        assertEquals(50, pagination.path("size").asInt(), pagination::toString);
        assertEquals(0, pagination.path("start_page").asInt(-1), pagination::toString);
        assertEquals("$.content", pagination.path("response_path").asText(), pagination::toString);

        String fromMinimum = "{ parameters: " + params("page", "minimum: 1") + ", responses: " + rowsIn("items") + " }";
        JsonNode one = onlyEndpoint(draft(spec(null, get("/things", fromMinimum)))).path("pagination");
        assertEquals(1, one.path("start_page").asInt(-1), one::toString);
        assertTrue(one.path("size_param").isMissingNode(), one::toString);
    }

    /** Starting at 1 on a source numbered from 0 skips a whole page and reports success. */
    @Test void page_numbers_with_no_declared_start_are_not_drafted() {
        String unsaid = "{ parameters: " + params("page", "", "size", "") + ", responses: " + rowsIn("content") + " }";
        String manifest = draft(spec(null, get("/things", unsaid) + get("/other", PAGED_GET)));
        assertEquals("other", onlyEndpoint(manifest).path("id").asText(), manifest);
        assertTrue(manifest.contains("# pagination: not drafted") && manifest.contains("start_page"), manifest);
        assertTrue(manifest.contains("#   path: \"/things\""), () -> "the undrafted endpoint stays visible, commented:\n" + manifest);
    }

    @Test void a_stripe_cursor_is_drafted_when_the_response_carries_has_more_and_row_ids() {
        String cursor = "{ parameters: " + params("limit", "", "starting_after", "")
            + ", responses: " + rowsIn("data", "has_more: { type: boolean }") + " }";
        JsonNode pagination = onlyEndpoint(draft(spec(null, get("/things", cursor)))).path("pagination");
        assertEquals("cursor", pagination.path("kind").asText(), pagination::toString);
        assertEquals("starting_after", pagination.path("cursor_param").asText(), pagination::toString);
        assertEquals("$.data[-1:].id", pagination.path("cursor_path").asText(), pagination::toString);
        assertEquals("$.has_more", pagination.path("has_more_path").asText(), pagination::toString);
        assertEquals("$.data", pagination.path("response_path").asText(), pagination::toString);
    }

    @Test void a_cursor_without_has_more_is_not_drafted() {
        String cursor = "{ parameters: " + params("starting_after", "") + ", responses: " + rowsIn("data") + " }";
        String manifest = draft(spec(null, get("/things", cursor) + get("/other", PAGED_GET)));
        assertEquals("other", onlyEndpoint(manifest).path("id").asText(), manifest);
        assertTrue(manifest.contains("# pagination: not drafted") && manifest.contains("has_more"), manifest);
    }

    /** offset stops on a short page: a limit above what the source serves would end the run after page one. */
    @Test void offset_paging_is_drafted_only_with_a_limit_the_spec_declares() {
        String declared = "{ parameters: " + params("offset", "", "limit", "default: 100")
            + ", responses: " + rowsIn("data", "total: { type: integer }") + " }";
        JsonNode pagination = onlyEndpoint(draft(spec(null, get("/things", declared)))).path("pagination");
        assertEquals("offset", pagination.path("kind").asText(), pagination::toString);
        assertEquals("offset", pagination.path("offset_param").asText(), pagination::toString);
        assertEquals("limit", pagination.path("limit_param").asText(), pagination::toString);
        assertEquals(100, pagination.path("limit").asInt(), pagination::toString);
        assertEquals("$.data", pagination.path("response_path").asText(), pagination::toString);

        String unsaid = "{ parameters: " + params("offset", "", "limit", "") + ", responses: " + rowsIn("data") + " }";
        String manifest = draft(spec(null, get("/things", unsaid) + get("/other", PAGED_GET)));
        assertEquals("other", onlyEndpoint(manifest).path("id").asText(), manifest);
        assertTrue(manifest.contains("# pagination: not drafted") && manifest.contains("limit"), manifest);
    }

    @Test void a_response_with_two_arrays_does_not_say_which_holds_the_rows() {
        String twoArrays = "{ parameters: " + params("page", "default: 0")
            + ", responses: " + rowsIn("content", "warnings: { type: array, items: { type: string } }") + " }";
        String manifest = draft(spec(null, get("/things", twoArrays) + get("/other", PAGED_GET)));
        assertEquals("other", onlyEndpoint(manifest).path("id").asText(), manifest);
        assertTrue(manifest.contains("# pagination: not drafted") && manifest.contains("response_path"), manifest);
    }

    @Test void a_path_with_no_paging_parameter_is_not_drafted() {
        String bare = "{ responses: " + rowsIn("data") + " }";
        String manifest = draft(spec(null, get("/things", bare) + get("/other", PAGED_GET)));
        assertEquals("other", onlyEndpoint(manifest).path("id").asText(), manifest);
        assertTrue(manifest.contains("# pagination: not drafted"), manifest);
    }

    // ---------------------------------------------------------------------------------------
    // The spec is the remote party's document, and `--apply` applies the draft unread. A value
    // that breaks out of its scalar or its comment line adds keys to the manifest: an injected
    // `headers: [{ name: X, value_env: ERP_DB_PASSWORD }]` would have the hub resolve a tenant
    // secret and send it to the spec author's base_url on the first run.
    // ---------------------------------------------------------------------------------------

    private static final String INJECTED = "\\n  headers: [{ name: X, value_env: ERP_DB_PASSWORD }]\\n#\\\"}, ";

    /** Every spec value the draft could write, each carrying a break-out payload. JSON, so the payload's escapes stay exact. */
    private static final String HOSTILE = """
        {
          "openapi": "3.0.3",
          "info": { "title": "Hostile", "version": "1" },
          "servers": [{ "url": "https://evil.example/api\\"%1$s" }],
          "components": { "securitySchemes": {
            "evilkey": { "type": "apiKey", "in": "header", "name": "X-Key%1$s" },
            "evilhttp": { "type": "http", "scheme": "digest%1$s" } } },
          "paths": {
            "/a%1$s": { "get": {
              "security": [],
              "parameters": [
                { "name": "page", "in": "query", "schema": { "type": "integer", "default": 0 } },
                { "name": "size%1$s", "in": "query", "schema": { "type": "integer", "default": 50 } }],
              "responses": %2$s } },
            "/b": { "get": {
              "security": [{ "evilkey": [] }],
              "parameters": [{ "name": "page", "in": "query", "schema": { "type": "integer", "default": 0 } }],
              "responses": %2$s } },
            "/c": { "get": {
              "security": [{ "evilhttp": [] }],
              "parameters": [{ "name": "page", "in": "query", "schema": { "type": "integer", "default": 0 } }],
              "responses": %2$s } },
            "/d": { "get": {
              "parameters": [{ "name": "page", "in": "query", "schema": { "type": "integer", "default": 0 } }],
              "responses": { "200": { "description": "ok", "content": { "application/json": { "schema": {
                "type": "object", "properties": { "rows%1$s": { "type": "array", "items": { "type": "object" } } } } } } } } } }
          }
        }
        """.formatted(INJECTED, """
            { "200": { "description": "ok", "content": { "application/json": { "schema": {
              "type": "object", "properties": { "data": { "type": "array", "items": { "type": "object" } } } } } } } }""");

    /** The payload as the spec holds it, once JSON has decoded it. */
    private static final String PAYLOAD = "\n  headers: [{ name: X, value_env: ERP_DB_PASSWORD }]\n#\"}, ";

    @Test @SuppressWarnings("unchecked")
    void a_hostile_spec_cannot_add_a_key_to_the_draft() {
        String manifest = new OpenApiImporter().importFromYaml(HOSTILE, "demo", List.of());
        Map<String, Object> doc;
        try {
            doc = new Yaml().load(manifest);
        } catch (RuntimeException e) {
            throw new AssertionError("the draft does not parse:\n" + manifest, e);
        }
        assertEquals(Set.of("apiVersion", "kind", "metadata", "spec"), doc.keySet(), manifest);
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        assertEquals(Set.of("base_url", "endpoints"), spec.keySet(), manifest);
        assertEquals("https://evil.example/api\"" + PAYLOAD, spec.get("base_url"), manifest);

        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) spec.get("endpoints");
        assertEquals(3, endpoints.size(), manifest);   // /d is not drafted: its rows key is not a name
        for (Map<String, Object> ep : endpoints) {
            assertEquals(Set.of("id", "method", "path", "pagination", "incremental", "target"), ep.keySet(), manifest);
            assertEquals(Set.of("kind", "page_param", "start_page", "response_path"),
                ((Map<String, Object>) ep.get("pagination")).keySet(), () -> "a hostile size param name is drafted:\n" + manifest);
        }
        assertEquals("/a" + PAYLOAD, endpoints.get(0).get("path"), manifest);

        // The api-key name is refused (a header cannot carry it); the http scheme is named by a fixed phrase.
        assertTrue(manifest.contains("# auth: this path asks for an API key named with characters a header cannot carry"), manifest);
        assertTrue(manifest.contains("# auth: this path asks for an http scheme other than basic or bearer"), manifest);
        assertTrue(manifest.contains("#   path: \"/d\""), manifest);
        // No line of the draft starts a key the importer did not write.
        manifest.lines().filter(l -> l.stripLeading().startsWith("headers")).findAny()
            .ifPresent(l -> fail("an injected line: " + l + "\n" + manifest));
    }

    /** YAML 1.1 also breaks lines on NEL and the Unicode separators: each value reads back exactly, on one line. */
    @Test void a_quoted_value_reads_back_exactly_and_stays_on_its_line() {
        String hostile = "a\\b\"c\td\re\nf\u0085g h i\u0000j\u007fk";
        String written = OpenApiImporter.quoted(hostile);
        assertEquals(1, written.lines().count(), written);
        assertFalse(written.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029), written);
        Map<String, Object> doc = new Yaml().load("k: " + written + "\nnext: 1\n");
        assertEquals(hostile, doc.get("k"));
        assertEquals(Set.of("k", "next"), doc.keySet());

        String line = OpenApiImporter.comment("# x\ny z\u0085w");
        assertEquals(1, line.lines().count(), line);
    }

    /** The shipped demo API's own /openapi.json (docs/connectors/rest/demo/acme-api.py), saved as served. */
    static String acmeDemoSpec() {
        try (InputStream in = OpenApiImporterTest.class.getResourceAsStream("/openapi/acme-demo-openapi.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The demo describes three paths. Only the Stripe cursor says enough to be drafted: the spec
     * gives /spring/partners no first page number and /open/partners no page size, so both stay
     * commented with what to declare — and the connector takes the cursor path's bearer token.
     */
    @Test void the_demo_spec_drafts_its_cursor_and_names_what_the_other_two_lack() {
        String manifest = new OpenApiImporter().importFromYaml(acmeDemoSpec(), "acme-from-openapi", List.of());
        assertEquals("bearer", parse(manifest).path("spec").path("auth").path("kind").asText(), manifest);
        JsonNode v1 = onlyEndpoint(manifest);
        assertEquals("v1-partners", v1.path("id").asText(), manifest);
        assertEquals("cursor", v1.path("pagination").path("kind").asText(), manifest);
        assertTrue(manifest.contains("#   path: \"/spring/partners\"") && manifest.contains("#   path: \"/open/partners\""), manifest);
        assertTrue(manifest.contains("# auth: this path asks for http basic"), manifest);
        assertTrue(manifest.contains("# auth: this path asks for no credentials"), manifest);
    }
}
