package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class CsvBuildSpecTest {

    // hooks-guide promises three mapping-hook kinds; the engine applies spec.dataset() first.
    // buildSpec must parse the dataset block, not silently drop it (tribunal case A3).
    @Test
    void mapping_dataset_hooks_are_parsed_into_the_spec() {
        Map<String, Object> config = Map.of("mapping", Map.of(
            "dataset", List.of(Map.of("filter-where", Map.of("column", "status", "equals", "active"))),
            "row", List.of(Map.of("map-columns", Map.of("rename", Map.of("cust_no", "customer_id")))),
            "cell", List.of(Map.of("upper", Map.of("columns", List.of("name"))))));

        MappingSpec spec = CsvFileConnectorType.buildSpec("clients", config);

        assertEquals(1, spec.dataset().size(), "dataset hooks must survive buildSpec");
        assertEquals("filter-where", spec.dataset().get(0).name());
        assertEquals(1, spec.row().size());
        assertEquals(1, spec.cell().size());
    }

    @Test
    void mapping_absent_yields_hookless_dump_spec() {
        MappingSpec spec = CsvFileConnectorType.buildSpec("clients", Map.of());
        assertTrue(spec.dataset().isEmpty());
        assertTrue(spec.row().isEmpty());
        assertTrue(spec.cell().isEmpty());
    }

    // Layer 2 of the DDL-injection fix (see RlsDdl): buildSpec prefixes `table` with `t_` to build
    // the destination hub table — refuse a non-identifier name here, before it can reach the DDL
    // DynamicTableWriter executes on the BYPASSRLS admin datasource. In practice
    // CsvFileConnectorApplyHandler already slugs the manifest name before it reaches this method,
    // but buildSpec is also callable directly (as this test does), so it must not trust its caller.
    @Test
    void rejects_a_table_name_containing_a_single_quote() {
        assertThatThrownBy(() -> CsvFileConnectorType.buildSpec("o'r", Map.of()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }

    @Test
    void rejects_a_table_name_containing_a_dollar_quote_tag() {
        assertThatThrownBy(() -> CsvFileConnectorType.buildSpec("orders$$evil", Map.of()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }
}
