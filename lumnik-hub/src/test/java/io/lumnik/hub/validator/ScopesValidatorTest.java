package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ValidationError.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scope moves to {@code metadata.scopes} and lives nowhere else — as tables.
 *
 * <p>Before this, the scope had two homes that merged ({@code metadata.tags} and
 * {@code spec.target.tags}), one of which never reached the merge for REST (its {@code target}
 * is per endpoint, the merge read the root). The fixture {@code csv-no-tags.yaml} carried
 * {@code metadata.tags: [crm]} and a test REQUIRED the "no tags defined" warning on it — the
 * warning lied, and the test pinned the lie. Every row below is one of those shapes, refused
 * in the reader's own terms.
 */
class ScopesValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode manifest(String metadataExtra, String specExtra) throws Exception {
        return JSON.readTree("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"name":"x","connector_type":"csv-file"%s},
             "spec":{%s}}""".formatted(metadataExtra, specExtra));
    }

    private record Moved(String name, String metadataExtra, String specExtra, String path) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> moved() {
        return Stream.of(
            new Moved("metadata.tags — the old name of the same field",
                ",\"tags\":[\"crm\"]", "", "metadata.tags"),
            new Moved("spec.target.tags — the CSV/JDBC root target",
                "", "\"target\":{\"tags\":[\"crm\"]}", "spec.target.tags"),
            new Moved("spec.endpoints[i].target.tags — the REST per-endpoint target",
                "", "\"endpoints\":[{\"id\":\"a\"},{\"id\":\"b\",\"target\":{\"tags\":[\"crm\"]}}]",
                "spec.endpoints[1].target.tags")
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("moved")
    void the_old_homes_are_refused_and_the_refusal_names_the_new_one(Moved m) throws Exception {
        List<ValidationError> errs = ScopesValidator.errors(manifest(m.metadataExtra(), m.specExtra()));

        ValidationError e = errs.stream().filter(x -> x.path().equals(m.path())).findFirst()
            .orElseThrow(() -> new AssertionError("expected an error at " + m.path() + ", got " + errs));
        assertEquals(Severity.ERROR, e.severity(), "a moved field is refused, not advised");
        assertTrue(e.message().contains("metadata.scopes"),
            () -> "the refusal must name where the field went: " + e.message());
    }

    private record Paste(String name, String metadataExtra, String wantLine) {
        @Override public String toString() { return name; }
    }

    /**
     * The refusal's paste line must be one the validator would then ACCEPT — a list, whatever
     * shape the old field had. Review caught {@code tags: null} rendering as {@code scopes: null},
     * which this very validator refuses one step later.
     */
    static Stream<Arguments> pasteLines() {
        return Stream.of(
            new Paste("a list is carried over", ",\"tags\":[\"crm\",\"billing\"]", "scopes: [crm, billing]"),
            new Paste("a scalar becomes a one-element list", ",\"tags\":\"crm\"", "scopes: [crm]"),
            new Paste("null becomes an empty list, never the word null", ",\"tags\":null", "scopes: []")
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pasteLines")
    void the_paste_line_is_always_a_list(Paste p) throws Exception {
        List<ValidationError> errs = ScopesValidator.errors(manifest(p.metadataExtra(), ""));

        ValidationError e = errs.stream().filter(x -> x.path().equals("metadata.tags")).findFirst()
            .orElseThrow(() -> new AssertionError("expected the moved-field refusal, got " + errs));
        assertTrue(e.recommendations().stream().anyMatch(r -> r.endsWith(p.wantLine())),
            () -> "want a recommendation ending in " + p.wantLine() + ", got " + e.recommendations());
    }

    private record Ident(String name, String scopesJson, boolean ok) {
        @Override public String toString() { return name; }
    }

    /**
     * One rule for every door that writes a scope name (ScopeGrants.IDENTIFIER). It is the strict
     * one: three copies used to exist, and the PUT endpoint's — lowercase, letter first, max 41 —
     * was the one an IT already pinned. A name becomes the case-sensitive role scope:<name>.
     */
    static Stream<Arguments> identifiers() {
        return Stream.of(
            new Ident("a plain word", "[\"compta\"]", true),
            new Ident("41 characters — the ceiling, inclusive", "[\"" + "a".repeat(41) + "\"]", true),
            new Ident("42 characters — over it", "[\"" + "a".repeat(42) + "\"]", false),
            new Ident("uppercase — CRM and crm would be two roles", "[\"CRM\"]", false),
            new Ident("a digit first", "[\"2024-sales\"]", false),
            new Ident("several", "[\"sales\",\"compta\"]", true),
            new Ident("digits, underscore, dash", "[\"erp-2_x\"]", true),
            new Ident("a blank entry", "[\"\"]", false),
            new Ident("whitespace inside", "[\"sales team\"]", false),
            new Ident("a colon — that is the ROLE's syntax (scope:sales), not the scope's", "[\"scope:sales\"]", false),
            new Ident("not a list", "\"compta\"", false),
            new Ident("a non-string entry", "[1]", false)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identifiers")
    void a_scope_is_a_simple_identifier(Ident i) throws Exception {
        List<ValidationError> errs = ScopesValidator.errors(manifest(",\"scopes\":" + i.scopesJson(), ""));

        boolean refused = errs.stream().anyMatch(e -> e.path().startsWith("metadata.scopes"));
        assertEquals(!i.ok(), refused, () -> "errors: " + errs);
    }

    @Test
    void no_scopes_is_advised_not_refused() throws Exception {
        JsonNode m = manifest("", "");

        assertTrue(ScopesValidator.errors(m).isEmpty(), "absence is legal");
        List<ValidationError> w = ScopesValidator.warnings(m);
        assertEquals(1, w.size());
        assertEquals("metadata.scopes", w.get(0).path());
        assertEquals(Severity.WARNING, w.get(0).severity());
        // What is lost on every edition (#1582): a bound caller is refused a scopeless connector
        // (ScopeGrants.allowsAll needs one tag). Ask is not in the open edition, so not named.
        assertEquals("no scopes declared — a user bound to a scope will not see this connector, its runs or its dead letters",
            w.get(0).message());
    }

    @Test
    void an_empty_list_is_advised_like_absence() throws Exception {
        assertEquals(1, ScopesValidator.warnings(manifest(",\"scopes\":[]", "")).size());
    }

    @Test
    void a_scoped_manifest_gets_no_advice() throws Exception {
        assertTrue(ScopesValidator.warnings(manifest(",\"scopes\":[\"compta\"]", "")).isEmpty());
    }
}
