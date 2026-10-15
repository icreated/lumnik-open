package io.lumnik.hub.search;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.search.SearchIndexManifest.Field;
import io.lumnik.hub.search.SearchIndexManifest.Match;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #669: fields are canonical columns of the referenced Entity — nothing else can reach the index. */
class SearchIndexDefinitionCheckTest {

    private static EntityManifest customer() {
        Map<String, String> crm = new LinkedHashMap<>();
        crm.put("cust_no", "code");
        crm.put("cust_name", "name");
        Map<String, String> erp = new LinkedHashMap<>();
        erp.put("kunnr", "code");
        erp.put("ort", "city");
        return new EntityManifest("Entity", "customer", List.of("code"),
            List.of(new EntityManifest.Source("t_crm_customer", crm),
                    new EntityManifest.Source("t_erp_customer", erp)));
    }

    private static SearchIndexManifest index(Field... fields) {
        return new SearchIndexManifest("customers", "customer", "simple", List.of(fields));
    }

    @Test void canonical_columns_from_any_source_are_accepted() {
        assertThat(SearchIndexDefinitionCheck.problem(
            index(new Field("name", Match.FUZZY, false), new Field("city", Match.EXACT, true)), customer()))
            .isEmpty();
    }

    @Test void a_source_column_name_is_not_a_canonical_column() {
        assertThat(SearchIndexDefinitionCheck.problem(index(new Field("cust_name", Match.TEXT, false)), customer()))
            .hasValueSatisfying(p -> assertThat(p).contains("spec.fields[0].name").contains("cust_name"));
    }

    @Test void an_invented_column_is_refused_and_named_at_its_position() {
        assertThat(SearchIndexDefinitionCheck.problem(
            index(new Field("name", Match.TEXT, false), new Field("salary", null, false)), customer()))
            .hasValueSatisfying(p -> assertThat(p).contains("spec.fields[1].name").contains("salary"));
    }

    @Test void a_column_dropped_from_the_entity_later_makes_the_index_invalid() {
        SearchIndexManifest m = index(new Field("city", Match.EXACT, false));
        assertThat(SearchIndexDefinitionCheck.problem(m, customer())).isEmpty();
        EntityManifest reapplied = new EntityManifest("Entity", "customer", List.of("code"),
            List.of(new EntityManifest.Source("t_crm_customer", Map.of("cust_no", "code", "cust_name", "name"))));
        assertThat(SearchIndexDefinitionCheck.problem(m, reapplied)).isPresent();
    }

    private static EntityManifest productWithCategory() {
        Map<String, String> products = new LinkedHashMap<>();
        products.put("pid", "product_id");
        products.put("cid", "category_id");
        products.put("name", "product_name");
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put("name", "category_name");
        return new EntityManifest("Entity", "product", null, List.of("product_id"),
            List.of(new EntityManifest.Source("t_products", products)), List.of(), null, Map.of(), null,
            List.of(new EntityManifest.Join("t_categories", "category_id", "cid", categories)));
    }

    @Test void a_joined_column_is_searchable_on_a_match_entity() {   // #847: Search now observes the lookup
        assertThat(SearchIndexDefinitionCheck.problem(
            new SearchIndexManifest("products", "product", "simple", List.of(new Field("category_name", Match.TEXT, false))),
            productWithCategory())).isEmpty();
    }

    @Test void a_fused_column_of_an_entity_that_also_joins_is_still_accepted() {
        assertThat(SearchIndexDefinitionCheck.problem(
            new SearchIndexManifest("products", "product", "simple", List.of(new Field("product_name", Match.TEXT, false))),
            productWithCategory())).isEmpty();
    }
}
