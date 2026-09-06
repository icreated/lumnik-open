package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.EnvelopeValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.rest.EndpointsBlockValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiImporterTest {

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
                              data: { type: array, items: { type: object } }
            """;
        String manifest = new OpenApiImporter().importFromYaml(yaml, "demo", List.of("test"));
        assertTrue(manifest.contains("name: demo"), manifest);
        assertTrue(manifest.contains("kind: cursor"), manifest);
        assertTrue(manifest.contains("path: /v1/things"), manifest);
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
                get: { responses: { "200": { description: ok } } }
              /order_items:
                get: { responses: { "200": { description: ok } } }
              /日本語:
                get: { responses: { "200": { description: ok } } }
            """;
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
              /things: { get: { responses: { "200": { description: ok } } } }
            """;
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
                get:
                  responses: { "200": { description: ok } }
            """;
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
                get:
                  responses: { "200": { description: ok } }
            """;
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
}
