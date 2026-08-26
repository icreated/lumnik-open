package io.lumnik.hub.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3, the process half (tribunal 2026-07-25 → 2026-07-27). Same silence as the view manifest, with
 * two more levels to close: {@code spec.on} and {@code spec.emit}. A misspelling inside either used
 * to no-op, and since the parser reads {@code on.event} to enforce the single v1 trigger, a typo
 * there would have been refused loudly anyway — while a typo in {@code emit.message} would not.
 */
class ProcessManifestUnknownKeysTest {

    private final ProcessManifestParser parser = new ProcessManifestParser();

    private static final String VALID = """
        kind: Process
        metadata:
          name: overdue-invoice
        spec:
          scope: compta
          on:
            event: data.row.changed
            table: connector.t_invoice
          when:
            - "amount > 1000"
          emit:
            type: decision.invoice.overdue
            message: "Facture en retard"
        """;

    @Test
    void a_complete_manifest_declares_nothing_unknown() {
        assertEquals(List.of(), parser.unknownKeys(VALID),
            "every key this manifest uses is honored — the inventory must not cry wolf");
    }

    @Test
    void the_nested_blocks_are_closed_too() {
        String yaml = VALID
            .replace("table: connector.t_invoice", "tabel: connector.t_invoice")
            .replace("message: \"Facture en retard\"", "mesage: \"Facture en retard\"");

        assertEquals(List.of("spec.on.tabel", "spec.emit.mesage"), parser.unknownKeys(yaml),
            "spec.on and spec.emit are closed, and each path is reported in full");
    }

    @Test
    void unknown_keys_are_reported_at_every_level() {
        String yaml = VALID.replace("spec:\n  scope: compta", "spec:\n  scope: compta\n  severity: high")
                           .replace("metadata:\n  name: overdue-invoice",
                                    "metadata:\n  name: overdue-invoice\n  owner: marie");

        assertEquals(List.of("metadata.owner", "spec.severity"), parser.unknownKeys(yaml),
            "metadata and spec are closed, in document order");
    }

    /**
     * The trap this whole verdict was cautious about. {@code parse()} never reads apiVersion, so
     * reading the parser alone yields an inventory that refuses every manifest the project ships —
     * {@code docs/demo/wow-customer/lyon-process.yaml} included, the dragon's first proven rung.
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
        String surface = ProcessManifestParser.honoredSurface();

        for (String key : List.of("apiVersion", "kind", "metadata", "spec", "name", "scope",
                                  "when", "on", "emit", "event", "table", "type", "message")) {
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
        // The inventory drifts the day someone adds a field to parse() and forgets it here.
        ProcessManifest m = parser.parse(VALID);
        assertEquals("overdue-invoice", m.name(), "metadata.name");
        assertEquals("compta", m.scope(), "spec.scope");
        assertEquals("data.row.changed", m.onEvent(), "spec.on.event");
        assertEquals("connector.t_invoice", m.onTable(), "spec.on.table");
        assertEquals(List.of("amount > 1000"), m.when(), "spec.when");
        assertEquals("decision.invoice.overdue", m.emitType(), "spec.emit.type");
        assertEquals("Facture en retard", m.emitMessage(), "spec.emit.message");
    }
}
