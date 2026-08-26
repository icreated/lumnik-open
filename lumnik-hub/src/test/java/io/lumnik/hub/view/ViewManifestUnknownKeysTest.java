package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3, the half left open (tribunal 2026-07-25 → 2026-07-27). Undeclared keys were rejected nowhere
 * in a view manifest: a misspelled {@code titel:} no-oped in silence and the integrator's title
 * simply never appeared. The connector blocks were closed first and view/process stayed open
 * because the verdict required a COMPLETE key inventory — a generic warning over a partial one
 * would cry wolf. This parser's inventory is complete and fits on one screen, so the refusal can
 * be exact.
 *
 * <p>Reporting only — the parser itself stays lenient, because saved views are stored as YAML and
 * a hard refusal at parse would break reading a view that is already in the store. The apply door
 * is where a manifest is refused.
 */
class ViewManifestUnknownKeysTest {

    private final ViewManifestParser parser = new ViewManifestParser();

    private static final String VALID = """
        kind: View
        metadata:
          name: clients-desk
        spec:
          scope: clients
          table: connector.v_customer
          title: Clients
          columns: [customer_code, name]
        """;

    @Test
    void a_complete_manifest_declares_nothing_unknown() {
        assertEquals(List.of(), parser.unknownKeys(VALID),
            "every key this manifest uses is honored — the inventory must not cry wolf");
    }

    @Test
    void the_misspelled_title_is_named() {
        String yaml = VALID.replace("title: Clients", "titel: Clients");

        assertEquals(List.of("spec.titel"), parser.unknownKeys(yaml),
            "the typo that started this case must be named with its path");
    }

    @Test
    void unknown_keys_are_reported_at_every_level() {
        String yaml = """
            kind: View
            metadata:
              name: clients-desk
              labels: {a: b}
            spec:
              scope: clients
              columns: [customer_code]
              sort: name
            """;

        assertEquals(List.of("metadata.labels", "spec.sort"), parser.unknownKeys(yaml),
            "root, metadata and spec are all closed, and the paths are reported in document order");
    }

    /**
     * The trap this whole verdict was cautious about. {@code parse()} never reads apiVersion, so
     * reading the parser alone yields an inventory that refuses every manifest the project ships —
     * {@code docs/demo/wow-customer/clients-view.yaml} included, the file a stranger replays.
     */
    @Test
    void the_apiVersion_envelope_every_shipped_manifest_carries_is_not_cried_wolf_on() {
        String yaml = "apiVersion: apps.lumnik.io/v1\n" + VALID;

        assertEquals(List.of(), parser.unknownKeys(yaml),
            "apiVersion is declared though unused — the WOW manifests would break otherwise");
    }

    /**
     * The refusal teaches by naming what IS honored, so a hand-typed list is one edit away from
     * contradicting the inventory it describes — and the first casualty would be {@code apiVersion},
     * accepted but easy to leave out. Derived, it cannot drift.
     */
    @Test
    void the_honored_surface_names_every_declared_key() {
        String surface = ViewManifestParser.honoredSurface();

        for (String key : List.of("apiVersion", "kind", "metadata", "spec",
                                  "name", "scope", "table", "title", "columns")) {
            assertTrue(surface.contains(key), key + " must appear in: " + surface);
        }
    }

    @Test
    void a_manifest_that_does_not_parse_reports_nothing_rather_than_guessing() {
        assertEquals(List.of(), parser.unknownKeys(":\n  not yaml at all\n:::"),
            "an unparseable manifest is the parser's error to raise, not this check's");
    }

    @Test
    void every_declared_key_is_actually_honored() {
        // The inventory drifts the day someone adds a field to parse() and forgets it here. Each
        // declared key must therefore be observable in the parsed manifest.
        ViewManifest m = parser.parse(VALID);
        assertEquals("clients-desk", m.name(), "metadata.name");
        assertEquals("clients", m.scope(), "spec.scope");
        assertEquals("connector.v_customer", m.table(), "spec.table");
        assertEquals("Clients", m.title(), "spec.title");
        assertEquals(List.of("customer_code", "name"), m.columns(), "spec.columns");
    }
}
