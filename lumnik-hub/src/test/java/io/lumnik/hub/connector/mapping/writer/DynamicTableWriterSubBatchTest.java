package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * É2.b Task 3 review — Bug B: the gated sub-batch must be clamped by column count so that
 * rows × colsPerRow never exceeds Postgres' 65535 bind-param cap on wide ERP tables.
 */
class DynamicTableWriterSubBatchTest {

    @Test
    void narrow_table_uses_the_full_upper_bound() {
        assertThat(DynamicTableWriter.subBatchSize(10)).isEqualTo(400);
    }

    @Test
    void wide_table_clamps_below_the_bind_param_cap() {
        // 65535 / 200 = 327 (integer division)
        assertThat(DynamicTableWriter.subBatchSize(200)).isEqualTo(327);
        // 327 rows × 200 cols = 65400 ≤ 65535
        assertThat(DynamicTableWriter.subBatchSize(200) * 200).isLessThanOrEqualTo(65535);
    }

    @Test
    void absurdly_wide_table_never_drops_below_one() {
        assertThat(DynamicTableWriter.subBatchSize(70000)).isEqualTo(1);
    }
}
