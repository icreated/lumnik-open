package io.lumnik.hub.search;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifest.ColumnMeta;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The human names of an entity's columns, as the Search answer carries them (#1055). */
class SearchFieldLabelsTest {

    private static EntityManifest entity(String name, Map<String, ColumnMeta> columns) {
        return new EntityManifest("Entity", name, null, List.of("code"), List.of(), List.of("stock"), null, columns, null);
    }

    private static ColumnMeta label(String label) { return new ColumnMeta(label, null, null, null); }

    private static final Map<String, EntityManifest> BY_NAME = Map.of(
        "Product", entity("Product", new LinkedHashMap<>(Map.of(
            "category_description", label("Catégorie"),
            "reference", label("  Référence  "),
            "blank", label("   "),
            "nolabel", new ColumnMeta(null, "a description only", null, null)))),
        "Plain", entity("Plain", Map.of()));

    @Test void only_columns_that_declare_a_label_are_listed_and_a_label_is_trimmed() {
        assertThat(SearchResource.fieldLabels(List.of("Product"), BY_NAME::get))
            .containsOnlyKeys("Product")
            .extracting(m -> m.get("Product"), InstanceOfAssertFactories.MAP)
            .containsOnly(Map.entry("category_description", "Catégorie"), Map.entry("reference", "Référence"));
    }

    @Test void an_entity_with_no_label_at_all_is_absent_not_empty() {
        assertThat(SearchResource.fieldLabels(List.of("Plain"), BY_NAME::get)).isEmpty();
    }

    @Test void an_unknown_entity_is_skipped_and_each_entity_is_listed_once() {
        assertThat(SearchResource.fieldLabels(List.of("Ghost", "Product", "Product"), BY_NAME::get))
            .containsOnlyKeys("Product");
    }
}
