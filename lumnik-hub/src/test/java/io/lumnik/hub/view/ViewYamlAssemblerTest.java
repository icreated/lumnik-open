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

        String yaml = assembler.assemble(m, List.of(), "les clients");

        ViewManifest back = parser.parse(yaml);
        assertEquals(m, back);
        assertDoesNotThrow(() -> resolver.resolve(back, card()));
    }

    @Test
    void quotingHostileTitle_survivesTheRoundTrip() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Clients: \"gold\" & #vip", List.of("name"));
        ViewManifest back = parser.parse(assembler.assemble(m, List.of(), "les clients"));
        assertEquals("Clients: \"gold\" & #vip", back.title());
    }

    @Test
    void nullTitle_isOmittedAndParsesBackAsNull() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            null, List.of("name"));
        ViewManifest back = parser.parse(assembler.assemble(m, List.of(), "les clients"));
        assertNull(back.title());
    }

    @Test
    void filters_rideAsTheSuggestedCommandCommentLine() {
        ViewManifest m = new ViewManifest("clients-gold-lyon", "clients", "connector.v_customer",
            "Clients gold de Lyon", List.of("customer_code", "name", "segment"));

        String yaml = assembler.assemble(m, List.of("segment=GOLD", "city~Lyon"), "les clients gold de Lyon");

        assertEquals("# lm view -f clients-gold-lyon.yaml --filter 'segment=GOLD' --filter 'city~Lyon'",
            commandLine(yaml));
        // The comment must not break parsing.
        assertEquals(m, parser.parse(yaml));
    }

    @Test
    void noFilters_theHeaderSaysSoInsteadOfSayingNothing() {
        // #489: a generated view can silently drop a condition of the description. When it drops
        // ALL of them there was no comment at all, so the one case where the gap is total was
        // also the one case the reader was told nothing about.
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        String yaml = assembler.assemble(m, List.of(), "les commandes en litige");

        assertEquals(List.of(
            "# asked for: les commandes en litige",
            "# kept: no condition — this view lists every row of connector.v_customer"),
            yaml.lines().takeWhile(l -> l.startsWith("#")).toList());
        assertEquals(m, parser.parse(yaml));
    }

    @Test
    void theHeaderEchoesTheDescriptionAndTheConditionsKept() {
        // The gap the ticket measured: "les commandes en litige de plus de 500 euros" came back
        // with amount>500 and no status filter, and nothing said the other half had been dropped.
        ViewManifest m = new ViewManifest("litiges", "desk", "connector.t_orders",
            "Litiges > 500", List.of("order_no", "amount"));

        String yaml = assembler.assemble(m, List.of("amount>500"),
            "les commandes en litige de plus de 500 euros");

        assertEquals(List.of(
            "# asked for: les commandes en litige de plus de 500 euros",
            "# kept: amount>500 — a condition of that description not listed here is NOT enforced",
            "# lm view -f litiges.yaml --filter 'amount>500'"),
            yaml.lines().takeWhile(l -> l.startsWith("#")).toList());
    }

    @Test
    void aMultiLineDescription_isCollapsedNotInjected() {
        // The description is user text landing in a YAML comment, and a comment ends at the first
        // line break: a second line would be read as manifest. Collapsed, never refused — it is
        // display text, not a value that means something.
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));

        String yaml = assembler.assemble(m, List.of(), "les clients\nkind: hacked\u2028et: aussi");

        assertEquals("# asked for: les clients kind: hacked et: aussi", yaml.lines().findFirst().orElseThrow());
        assertEquals(m, parser.parse(yaml));
    }

    @Test
    void aVeryLongDescription_isClippedToKeepTheHeaderReadable() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        String yaml = assembler.assemble(m, List.of(), "x".repeat(400));
        String first = yaml.lines().findFirst().orElseThrow();
        assertTrue(first.endsWith("…"), first);
        assertTrue(first.length() < 240, "header line length " + first.length());
    }

    /** The ready-to-run command line of the header, wherever it sits among the comments. */
    private static String commandLine(String yaml) {
        return yaml.lines().filter(l -> l.startsWith("# lm view")).findFirst().orElseThrow();
    }

    @Test
    void apostropheInAFilterValue_isPosixEscapedInTheComment() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        String yaml = assembler.assemble(m, List.of("name~L'Oréal"), "les clients L'Oréal");
        Assertions.assertEquals(
            "# lm view -f desk.yaml --filter 'name~L'\\''Oréal'", commandLine(yaml));
    }

    @Test
    void lineBreakInAFilterValue_isRefusedNeverEmitted() {
        // A multi-line value would split the comment and inject raw YAML above the manifest
        // (e.g. a second `kind:` key) — the one-line comment invariant must refuse it loudly.
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("name~a\nkind: hacked"), "d"));
        assertTrue(e.getMessage().contains("line break"));

        IllegalArgumentException cr = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("name~a\rkind: hacked"), "d"));
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
                () -> assembler.assemble(m, List.of("name~a" + sep + "kind: hacked"), "d"),
                "separator U+" + Integer.toHexString(sep.charAt(0)));
            assertTrue(e.getMessage().contains("line break"));
        }
    }

    @Test
    void aNullOrBlankFilter_isAClearRefusalNotAnNpe() {
        ViewManifest m = new ViewManifest("desk", "clients", "connector.v_customer",
            "Desk", List.of("name"));

        IllegalArgumentException nul = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, Arrays.asList((String) null), "d"));
        assertTrue(nul.getMessage().contains("null or blank"));

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> assembler.assemble(m, List.of("   "), "d"));
        assertTrue(blank.getMessage().contains("null or blank"));
    }
}
