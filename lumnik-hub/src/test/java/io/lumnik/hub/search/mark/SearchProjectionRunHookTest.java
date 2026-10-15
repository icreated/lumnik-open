package io.lumnik.hub.search.mark;

import io.lumnik.hub.entity.EntityManifest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JUnit unit test for {@link SearchProjectionRunHook#physicalMatchColumns} — the one piece of
 * this trigger's logic that is pure (no JDBC): given an entity's declared {@code match} columns and
 * one {@code Source}'s physical&harr;canonical map, which physical columns to read and in which order.
 * Everything else in {@link SearchProjectionRunHook} is inherently JDBC-shaped (a scan, a mark, a
 * watermark advance, all inside one transaction) with no clean further extraction.
 */
class SearchProjectionRunHookTest {

    private static EntityManifest.Source source(String table, Map<String, String> physicalToCanonical) {
        return new EntityManifest.Source(table, new LinkedHashMap<>(physicalToCanonical));
    }

    @Test
    void ordersPhysicalColumnsToMatchTheDeclaredMatchColumnOrder() {
        EntityManifest m = new EntityManifest("Entity", "Customer", List.of("code", "region"), List.of());
        EntityManifest.Source s = source("t_customers_erp", Map.of(
            "SRC_REGION", "region",
            "SRC_CODE", "code",
            "SRC_NAME", "name"));

        List<String> physical = SearchProjectionRunHook.physicalMatchColumns(m, s);

        assertThat(physical).containsExactly("SRC_CODE", "SRC_REGION");
    }

    @Test
    void singleMatchColumn() {
        EntityManifest m = new EntityManifest("Entity", "Product", List.of("sku"), List.of());
        EntityManifest.Source s = source("t_products_erp", Map.of("SRC_SKU", "sku", "SRC_LABEL", "label"));

        assertThat(SearchProjectionRunHook.physicalMatchColumns(m, s)).containsExactly("SRC_SKU");
    }

    @Test
    void returnsNullWhenTheSourceDoesNotMapEveryMatchColumn() {
        EntityManifest m = new EntityManifest("Entity", "Customer", List.of("code", "region"), List.of());
        // this source maps 'code' but not 'region' -- an unvalidated/inconsistent manifest edge case
        EntityManifest.Source s = source("t_customers_erp", Map.of("SRC_CODE", "code"));

        assertThat(SearchProjectionRunHook.physicalMatchColumns(m, s)).isNull();
    }

    @Test
    void returnsNullWhenTheSourceHasNoMap() {
        EntityManifest m = new EntityManifest("Entity", "Customer", List.of("code"), List.of());
        EntityManifest.Source s = new EntityManifest.Source("t_customers_erp", null);

        assertThat(SearchProjectionRunHook.physicalMatchColumns(m, s)).isNull();
    }
}
