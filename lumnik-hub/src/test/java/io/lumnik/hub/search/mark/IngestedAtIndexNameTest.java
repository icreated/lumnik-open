package io.lumnik.hub.search.mark;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IngestedAtIndexNameTest {

    @Test
    void aShortTableKeepsItsPlainName() {
        assertThat(IngestedAtIndex.indexName("t_orders")).isEqualTo("t_orders_ing_idx");
    }

    @Test
    void aLongNameFitsPostgresAndIsStable() {
        String table = "t_" + "x".repeat(70);
        String name = IngestedAtIndex.indexName(table);
        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(63);
        assertThat(name).endsWith("_ing_idx").isEqualTo(IngestedAtIndex.indexName(table));
    }

    @Test
    void longNamesWithTheSameJavaHashCodeGetDifferentIndexNames() {
        String prefix = "t_" + "p".repeat(44);
        String a = prefix + "AaAaAaAaAa";
        String b = prefix + "BBBBBBBBBB";
        assertThat(a.hashCode()).as("precondition: a 32-bit hash collides here").isEqualTo(b.hashCode());
        assertThat(IngestedAtIndex.indexName(a)).isNotEqualTo(IngestedAtIndex.indexName(b));
    }
}
