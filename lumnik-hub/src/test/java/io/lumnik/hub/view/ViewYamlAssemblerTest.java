package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ViewYamlAssemblerTest {

    private final ViewYamlAssembler assembler = new ViewYamlAssembler();
    private final ViewManifestParser parser = new ViewManifestParser();
    private final ViewResolver resolver = new ViewResolver();

    private static SchemaCard card() {
        return new SchemaCard("clients", List.of(
            new SchemaCard.Table("connector.v_customer", "Clients", List.of(
                new SchemaCard.Column("customer_code", "text", "Code client"),
                new SchemaCard.Column("name", "text", "Nom"),
                new SchemaCard.Column("segment", "text", "Segment")))));
    }

    @Test
    void roundTrip_theAssembledYamlParsesAndResolvesToTheSameManifest() {
        // THE keystone invariant: a generated manifest is indistinguishable from a hand-written one.
        ViewManifest m = new ViewManifest("clients-gold", "clients", "connector.v_customer",
            "Clients gold", List.of("customer_code", "name", "segment"));

        String yaml = assembler.assemble(m, List.of());

        ViewManifest back = parser.parse(yaml);
        assertEquals(m, back);
        assertDoesNotThrow(() -> resolver.resolve(back, card()));
    }

    @Test
    void quotingHostileTitle_survivesTheRoundTrip() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Clients: \"gold\" & #vip", List.of("name"));
        ViewManifest back = parser.parse(assembler.assemble(m, List.of()));
        assertEquals("Clients: \"gold\" & #vip", back.title());
    }

    @Test
    void nullTitle_isOmittedAndParsesBackAsNull() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            null, List.of("name"));
        ViewManifest back = parser.parse(assembler.assemble(m, List.of()));
        assertNull(back.title());
    }

    @Test
    void filters_rideAsTheSuggestedCommandCommentLine() {
        ViewManifest m = new ViewManifest("clients-gold-lyon", "clients", "connector.v_customer",
            "Clients gold de Lyon", List.of("customer_code", "name", "segment"));

        String yaml = assembler.assemble(m, List.of("segment=GOLD", "city~Lyon"));

        String firstLine = yaml.lines().findFirst().orElseThrow();
        assertEquals("# lm view -f clients-gold-lyon.yaml --filter 'segment=GOLD' --filter 'city~Lyon'",
            firstLine);
        // The comment must not break parsing.
        assertEquals(m, parser.parse(yaml));
    }

    @Test
    void noFilters_noCommentLine() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        String yaml = assembler.assemble(m, List.of());
        assertFalse(yaml.startsWith("#"));
        assertTrue(yaml.startsWith("apiVersion:"));
    }

    @Test
    void apostropheInAFilterValue_isPosixEscapedInTheComment() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        String yaml = assembler.assemble(m, List.of("name~L'Oréal"));
        String firstLine = yaml.lines().findFirst().orElseThrow();
        Assertions.assertEquals(
            "# lm view -f desk.yaml --filter 'name~L'\\''Oréal'", firstLine);
    }

    @Test
    void lineBreakInAFilterValue_isRefusedNeverEmitted() {
        // A multi-line value would split the comment and inject raw YAML above the manifest
        // (e.g. a second `kind:` key) — the one-line comment invariant must refuse it loudly.
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("name~a\nkind: hacked")));
        assertTrue(e.getMessage().contains("line break"));

        IllegalArgumentException cr = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("name~a\rkind: hacked")));
        assertTrue(cr.getMessage().contains("line break"));
    }

    @Test
    void unicodeLineSeparators_areRefusedLikeNewlines() {
        // SnakeYAML (YAML 1.1) also ends a comment at NEL/LS/PS — the same injection, in
        // Unicode clothing.
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        for (String sep : List.of("\u0085", "\u2028", "\u2029")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(m, List.of("name~a" + sep + "kind: hacked")),
                "separator U+" + Integer.toHexString(sep.charAt(0)));
            assertTrue(e.getMessage().contains("line break"));
        }
    }

    @Test
    void aNullOrBlankFilter_isAClearRefusalNotAnNpe() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));

        IllegalArgumentException nul = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, Arrays.asList((String) null)));
        assertTrue(nul.getMessage().contains("null or blank"));

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("   ")));
        assertTrue(blank.getMessage().contains("null or blank"));
    }
}
