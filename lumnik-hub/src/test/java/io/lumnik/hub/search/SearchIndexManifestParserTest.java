package io.lumnik.hub.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.search.SearchIndexManifest.Field;
import io.lumnik.hub.search.SearchIndexManifest.Match;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #669: kind:SearchIndex is fail-closed — only what is declared may leave PostgreSQL. */
class SearchIndexManifestParserTest {

    private final SearchIndexManifestParser parser = new SearchIndexManifestParser();

    private static final String VALID = """
        apiVersion: search.lumnik.io/v1
        kind: SearchIndex
        metadata:
          name: customers
        spec:
          entity: customer
          language: french
          fields:
            - name: name
              match: fuzzy
            - name: city
              match: exact
              facet: true
            - name: segment
              facet: true
        """;

    private static String withSpec(String spec) {
        return """
            apiVersion: search.lumnik.io/v1
            kind: SearchIndex
            metadata:
              name: customers
            spec:
            """ + spec.indent(2);
    }

    private static final String ONE_FIELD = """
        entity: customer
        fields:
          - name: name
            match: text
        """;

    // --- the happy path -------------------------------------------------------------------

    @Test void a_valid_manifest_keeps_the_declared_fields_in_order() {
        SearchIndexManifest m = parser.parse(VALID);
        assertThat(m.name()).isEqualTo("customers");
        assertThat(m.entity()).isEqualTo("customer");
        assertThat(m.language()).isEqualTo("french");
        assertThat(m.fields()).containsExactly(
            new Field("name", Match.FUZZY, false),
            new Field("city", Match.EXACT, true),
            new Field("segment", null, true));
    }

    @Test void a_field_without_match_is_returned_but_not_searched() {
        SearchIndexManifest m = parser.parse(VALID);
        assertThat(m.searchableFields()).containsExactly("name", "city");
        assertThat(m.returnableFields()).containsExactly("name", "city", "segment");
        assertThat(m.facetFields()).containsExactly("city", "segment");
    }

    @Test void every_searchable_or_facet_field_is_returnable_by_construction() {
        SearchIndexManifest m = parser.parse(VALID);
        assertThat(m.returnableFields()).containsAll(m.searchableFields()).containsAll(m.facetFields());
    }

    // --- D6: searchable ⊆ returnable, facet ⊆ returnable; return-only fields allowed ------

    @Test void a_return_only_field_without_match_or_facet_is_allowed() {
        SearchIndexManifest m = parser.parse(withSpec(ONE_FIELD + "  - name: code\n"));
        assertThat(m.fields()).contains(new Field("code", null, false));
        assertThat(m.returnableFields()).containsExactly("name", "code");
        assertThat(m.searchableFields()).containsExactly("name");
        assertThat(m.facetFields()).isEmpty();
    }

    @Test void searchable_and_facet_fields_are_subsets_of_returnable_for_any_declaration() {
        String[] specs = {
            VALID,
            withSpec(ONE_FIELD),
            withSpec(ONE_FIELD + "  - name: code\n  - name: city\n    facet: true\n"),
        };
        for (String yaml : specs) {
            SearchIndexManifest m = parser.parse(yaml);
            assertThat(m.returnableFields()).containsAll(m.searchableFields()).containsAll(m.facetFields());
        }
    }

    @Test void the_model_cannot_express_a_searchable_field_that_is_not_returned() {
        // Returnable is derived from the declaration itself, so a hand-built manifest holds too.
        SearchIndexManifest m = new SearchIndexManifest("i", "e", "simple",
            List.of(new Field("a", Match.TEXT, false), new Field("b", null, true)));
        assertThat(m.returnableFields()).containsAll(m.searchableFields()).containsAll(m.facetFields());
    }

    // --- titleField (#1044) -----------------------------------------------------------------

    private static final String TWO_FIELDS = """
        entity: product
        %s
        fields:
          - name: reference
            match: prefix
          - name: description
            match: text
        """;

    @Test void a_declared_title_field_is_kept() {
        assertThat(parser.parse(withSpec(TWO_FIELDS.formatted("titleField: reference"))).titleField())
            .isEqualTo("reference");
    }

    @Test void the_title_field_is_optional_and_never_guessed_from_field_order() {
        assertThat(parser.parse(withSpec(TWO_FIELDS.formatted(""))).titleField()).isNull();
    }

    @Test void a_title_field_that_is_not_a_declared_field_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec(TWO_FIELDS.formatted("titleField: nope"))))
            .hasMessageContaining("spec.titleField").hasMessageContaining("nope")
            .hasMessageContaining("spec.fields");
    }

    @Test void a_title_field_must_be_text() {
        assertThatThrownBy(() -> parser.parse(withSpec(TWO_FIELDS.formatted("titleField: [reference]"))))
            .hasMessageContaining("spec.titleField");
        assertThatThrownBy(() -> parser.parse(withSpec(TWO_FIELDS.formatted("titleField: ''"))))
            .hasMessageContaining("spec.titleField");
    }

    @Test void a_return_only_field_can_be_the_title() {
        String spec = """
            entity: product
            titleField: label
            fields:
              - name: reference
                match: exact
              - name: label
            """;
        assertThat(parser.parse(withSpec(spec)).titleField()).isEqualTo("label");
    }

    @Test void title_field_is_part_of_the_honored_surface() {
        assertThat(SearchIndexManifestParser.honoredSurface()).contains("titleField");
    }

    // --- links (#1051) ----------------------------------------------------------------------

    private static String linked(String links) {
        return TWO_FIELDS.formatted("links:\n" + links.indent(2).stripTrailing());
    }

    @Test void a_declared_link_is_kept() {
        var m = parser.parse(withSpec(linked("- field: reference\n  url: \"https://erp.example.com/articles/{reference}\"")));
        assertThat(m.links()).containsExactly(
            new SearchIndexManifest.Link("reference", "https://erp.example.com/articles/{reference}"));
    }

    @Test void links_are_optional() {
        assertThat(parser.parse(withSpec(TWO_FIELDS.formatted(""))).links()).isEmpty();
    }

    @Test void a_placeholder_may_name_any_declared_field_not_only_the_linked_one() {
        var m = parser.parse(withSpec(linked(
            "- field: description\n  url: \"https://erp.example.com/a/{reference}?q={description}\"")));
        assertThat(m.links()).hasSize(1);
    }

    @Test void a_link_on_an_undeclared_field_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec(linked("- field: nope\n  url: \"https://e.example.com/{reference}\""))))
            .hasMessageContaining("spec.links[0].field").hasMessageContaining("nope").hasMessageContaining("spec.fields");
    }

    @Test void a_link_field_is_matched_exactly_like_titleField_never_trimmed() {
        assertThatThrownBy(() -> parser.parse(withSpec(linked(
            "- field: \"reference \"\n  url: \"https://e.example.com/{reference}\""))))
            .hasMessageContaining("spec.links[0].field").hasMessageContaining("is not one of spec.fields");
    }

    @Test void a_placeholder_that_is_not_a_declared_field_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec(linked("- field: reference\n  url: \"https://e.example.com/{ghost}\""))))
            .hasMessageContaining("spec.links[0].url").hasMessageContaining("{ghost}").hasMessageContaining("spec.fields");
    }

    @Test void the_url_follows_the_one_external_link_rules() {
        // not https · a placeholder in the host · credentials in the address · an unclosed brace
        String[] refused = {"http://e.example.com/{reference}", "https://{reference}.example.com/x",
                            "https://user:pw@e.example.com/{reference}", "https://e.example.com/{reference"};
        for (String bad : refused) {
            assertThatThrownBy(() -> parser.parse(withSpec(linked("- field: reference\n  url: \"" + bad + "\""))))
                .as(bad).hasMessageContaining("spec.links[0].url");
        }
    }

    @Test void a_link_needs_a_url_and_nothing_else() {
        assertThatThrownBy(() -> parser.parse(withSpec(linked("- field: reference"))))
            .hasMessageContaining("spec.links[0].url");
        // a View-only key is not a SearchIndex key: the strict door names it
        var root = parser.readTree(withSpec(linked(
            "- field: reference\n  url: \"https://e.example.com/{reference}\"\n  targetView: x")));
        assertThat(parser.unknownKeys(root)).containsExactly("spec.links[0].targetView");
    }

    @Test void one_link_per_field() {
        assertThatThrownBy(() -> parser.parse(withSpec(linked(
            "- field: reference\n  url: \"https://e.example.com/a/{reference}\"\n"
          + "- field: reference\n  url: \"https://e.example.com/b/{reference}\""))))
            .hasMessageContaining("spec.links[1].field").hasMessageContaining("already carries a link");
    }

    @Test void at_most_sixteen_links() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 17; i++) many.append("- field: reference\n  url: \"https://e.example.com/{reference}\"\n");
        assertThatThrownBy(() -> parser.parse(withSpec(linked(many.toString()))))
            .hasMessageContaining("spec.links").hasMessageContaining("16");
    }

    @Test void links_must_be_a_list_of_mappings() {
        assertThatThrownBy(() -> parser.parse(withSpec(TWO_FIELDS.formatted("links: nope"))))
            .hasMessageContaining("spec.links must be a list");
        assertThatThrownBy(() -> parser.parse(withSpec(TWO_FIELDS.formatted("links: [nope]"))))
            .hasMessageContaining("spec.links[0] must be a mapping");
    }

    @Test void links_are_part_of_the_honored_surface_and_an_unknown_link_key_is_named() {
        assertThat(SearchIndexManifestParser.honoredSurface()).contains("links").contains("spec.links[]");
        var root = parser.readTree(withSpec(linked(
            "- field: reference\n  url: \"https://e.example.com/{reference}\"\n  target: x")));
        assertThat(parser.unknownKeys(root)).containsExactly("spec.links[0].target");
    }

    @Test void language_defaults_to_simple() {
        assertThat(parser.parse(withSpec(ONE_FIELD)).language()).isEqualTo("simple");
    }

    @Test void the_match_vocabulary_is_exact_text_fuzzy_prefix() {
        for (String match : new String[] {"exact", "text", "fuzzy", "prefix"}) {
            String yaml = withSpec(ONE_FIELD.replace("match: text", "match: " + match));
            assertThat(parser.parse(yaml).fields().get(0).match().name()).isEqualToIgnoringCase(match);
        }
    }

    // --- envelope ---------------------------------------------------------------------------

    @Test void api_version_is_strictly_validated() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("search.lumnik.io/v1", "apps.lumnik.io/v1")))
            .hasMessageContaining("apiVersion");
        assertThatThrownBy(() -> parser.parse(VALID.replace("apiVersion: search.lumnik.io/v1\n", "")))
            .hasMessageContaining("apiVersion");
    }

    @Test void kind_must_be_search_index() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("kind: SearchIndex", "kind: Export")))
            .hasMessageContaining("kind");
    }

    @Test void a_non_mapping_document_is_refused_cleanly() {
        assertThatThrownBy(() -> parser.parse("- a\n- b\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void name_and_entity_are_required_strings() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("  name: customers\n", "")))
            .hasMessageContaining("metadata.name");
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD.replace("entity: customer\n", ""))))
            .hasMessageContaining("spec.entity");
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD.replace("entity: customer", "entity: [customer]"))))
            .hasMessageContaining("spec.entity");
    }

    @Test void unknown_keys_are_reported_down_to_each_field() {
        JsonNode root = parser.readTree(VALID.replace("facet: true\n    - name: segment",
                                                      "facet: true\n      boost: 2\n    - name: segment"));
        assertThat(parser.unknownKeys(root)).containsExactly("spec.fields[1].boost");
    }

    // --- authorization cannot be redeclared -------------------------------------------------

    @Test void scopes_are_refused_because_they_are_inherited_from_the_entity() {
        assertThatThrownBy(() -> parser.parse(withSpec("scope: crm\n" + ONE_FIELD)))
            .hasMessageContaining("inherited from the entity");
        assertThatThrownBy(() -> parser.parse(withSpec("scopes: [crm]\n" + ONE_FIELD)))
            .hasMessageContaining("inherited from the entity");
    }

    @Test void the_named_refusals_are_available_before_the_unknown_key_check() {
        // The door runs this first: spec.scopes / spec.table are never in the honored surface, so
        // the generic unknown-key refusal would otherwise reach them first and hide why.
        assertThat(parser.namedRefusal(parser.readTree(withSpec("scopes: [crm]\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).contains("inherited from the entity"));
        assertThat(parser.namedRefusal(parser.readTree(withSpec("table: t_customers\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).contains("kind: Entity"));
        assertThat(parser.namedRefusal(parser.readTree(VALID))).isEmpty();
    }

    @Test void a_named_refusal_names_the_exact_key_the_integrator_wrote() {
        assertThat(parser.namedRefusal(parser.readTree(withSpec("scope: crm\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).startsWith("spec.scope:"));
        assertThat(parser.namedRefusal(parser.readTree(withSpec("scopes: [crm]\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).startsWith("spec.scopes:"));
        assertThat(parser.namedRefusal(parser.readTree(withSpec("table: t_customers\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).startsWith("spec.table:"));
        assertThat(parser.namedRefusal(parser.readTree(withSpec("source:\n  table: t\n" + ONE_FIELD))))
            .hasValueSatisfying(m -> assertThat(m).startsWith("spec.source:"));
    }

    @Test void a_raw_table_source_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec("table: t_customers\n" + ONE_FIELD)))
            .hasMessageContaining("kind: Entity");
        assertThatThrownBy(() -> parser.parse(withSpec("source:\n  table: t_customers\n" + ONE_FIELD)))
            .hasMessageContaining("kind: Entity");
    }

    // --- fields: the allowlist --------------------------------------------------------------

    @Test void at_least_one_field_is_required() {
        assertThatThrownBy(() -> parser.parse(withSpec("entity: customer\nfields: []\n")))
            .hasMessageContaining("spec.fields");
        assertThatThrownBy(() -> parser.parse(withSpec("entity: customer\n")))
            .hasMessageContaining("spec.fields");
    }

    @Test void at_least_one_field_must_be_searchable() {
        assertThatThrownBy(() -> parser.parse(withSpec("""
            entity: customer
            fields:
              - name: city
                facet: true
            """))).hasMessageContaining("searchable");
    }

    @Test void a_duplicate_field_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD + "  - name: name\n    match: exact\n")))
            .hasMessageContaining("duplicate");
    }

    @Test void internal_columns_can_never_be_indexed() {
        for (String internal : new String[] {"_canonical_key", "_conflicts", "_entity_id", "_resolution", "tenant_id"}) {
            assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD.replace("name: name", "name: " + internal))))
                .as(internal).hasMessageContaining("internal");
        }
    }

    @Test void an_unknown_match_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD.replace("match: text", "match: regex"))))
            .hasMessageContaining("exact, text, fuzzy, prefix");
    }

    @Test void phonetic_is_refused_as_deferred_not_as_a_typo() {
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD.replace("match: text", "match: phonetic"))))
            .hasMessageContaining("not supported yet")
            .hasMessageNotContaining("#");
    }

    @Test void facet_must_be_a_boolean() {
        assertThatThrownBy(() -> parser.parse(withSpec(ONE_FIELD + "    facet: yes please\n")))
            .hasMessageContaining("facet");
    }

    @Test void an_unsupported_language_is_refused() {
        assertThatThrownBy(() -> parser.parse(withSpec("language: klingon\n" + ONE_FIELD)))
            .hasMessageContaining("language");
    }
}
