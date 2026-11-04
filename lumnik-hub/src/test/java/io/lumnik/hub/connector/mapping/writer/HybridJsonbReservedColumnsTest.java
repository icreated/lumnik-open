package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** #1781: the validator refuses a promote entry named like a column of the hybrid table — the set must be the table's. */
class HybridJsonbReservedColumnsTest {

    @Test
    void theReservedColumns_areExactlyTheOnesTheTableHasBesideThePromotedOnes() {
        assertThat(new HashSet<>(DynamicTableSystemColumnsTest.columnsOf(HybridJsonbWriter.createTableSql("ext.t", List.of()))))
            .isEqualTo(HybridJsonbSpec.RESERVED_COLUMNS);
    }

    @Test
    void aPromotedColumn_isAddedToThem_notOneOfThem() {
        List<PromotedColumn> promote = List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty()));

        assertThat(DynamicTableSystemColumnsTest.columnsOf(HybridJsonbWriter.createTableSql("ext.t", promote)))
            .containsAll(HybridJsonbSpec.RESERVED_COLUMNS).contains("amount");
    }
}
