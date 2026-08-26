package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer 2 of the DDL-injection fix (see {@code RlsDdl}): {@code parseHybridSpec} reads
 * {@code target.table} verbatim from a REST/hybrid connector manifest and hands it straight to
 * {@code HybridJsonbWriter.createTable}'s DDL, executed on the BYPASSRLS admin datasource — this
 * path bypasses {@code MappingLoader.parseTable} entirely, so refuse it here too.
 */
class RestEndpointWriterHybridTableValidationTest {

    private final RestEndpointWriter writer = new RestEndpointWriter(null);

    @Test
    void rejects_a_table_containing_a_single_quote() {
        assertThatThrownBy(() -> writer.parseHybridSpec(Map.of("table", "ext.o'r", "unique_key", "$.id")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("table");
    }

    @Test
    void rejects_a_table_containing_a_dollar_quote_tag() {
        assertThatThrownBy(() -> writer.parseHybridSpec(Map.of("table", "ext.orders$$evil", "unique_key", "$.id")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("table");
    }

    @Test
    void accepts_a_plain_schema_qualified_table() {
        HybridJsonbSpec spec = writer.parseHybridSpec(Map.of("table", "ext.stripe_charges", "unique_key", "$.id"));
        assertThat(spec.table()).isEqualTo("ext.stripe_charges");
    }
}
