package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicTableRegistryTest {

    // Purge quotes through SqlIdentifiers; that escaping is pinned by SqlIdentifiersTest.

    @Test
    void recognises_only_hub_dynamic_tables() {
        // The blast-radius guard: purge drops only connector.t_* tables.
        assertThat(DynamicTableRegistry.isHubDynamicTable("connector", "t_order")).isTrue();
        assertThat(DynamicTableRegistry.isHubDynamicTable("connector", "t_my-table")).isTrue();
    }

    @Test
    void refuses_tables_outside_the_dynamic_convention() {
        assertThat(DynamicTableRegistry.isHubDynamicTable("connector", "connector")).isFalse(); // no t_ prefix
        assertThat(DynamicTableRegistry.isHubDynamicTable("identity", "t_user")).isFalse();      // wrong schema
        assertThat(DynamicTableRegistry.isHubDynamicTable("core", "event_outbox")).isFalse();
        assertThat(DynamicTableRegistry.isHubDynamicTable("connector", null)).isFalse();
    }

    /** #1821: the hash column is told by the registry row's mapping marker, with no query. */
    @Test
    void the_row_hash_column_follows_the_mapping_marker() {
        assertThat(DynamicTableRegistry.rowHashColumnOf("hybrid:ext.orders")).isEqualTo("row_hash");
        assertThat(DynamicTableRegistry.rowHashColumnOf("m-orders-csv")).isEqualTo("_row_hash");
        assertThat(DynamicTableRegistry.rowHashColumnOf(null)).isEqualTo("_row_hash");
    }
}
