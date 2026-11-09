package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderColumnTest {

    @ParameterizedTest
    @CsvSource({
        "date, DATE", "timestamp without time zone, TIMESTAMP", "timestamp with time zone, TIMESTAMPTZ",
        "numeric, DECIMAL", "integer, INTEGER", "bigint, INTEGER", "smallint, INTEGER", "real, FLOAT",
        "double precision, FLOAT", "text, TEXT", "character varying, TEXT", "character, TEXT"})
    void types_that_order(String dataType, OrderColumn kind) {
        assertThat(OrderColumn.of(dataType)).isEqualTo(kind);
    }

    @Test
    void types_that_do_not() {
        assertThat(OrderColumn.of("boolean")).isNull();
        assertThat(OrderColumn.of("jsonb")).isNull();
        assertThat(OrderColumn.of(null)).isNull();
    }

    @Test
    void the_problem_says_why() {
        assertThat(OrderColumn.problem("connector.v_x", "occurred_at", null))
            .isEqualTo("spec.on.order occurred_at is not a column of connector.v_x");
        assertThat(OrderColumn.problem("connector.v_x", "flag", "boolean"))
            .isEqualTo("spec.on.order flag is boolean: a date, a timestamp, a number, or ISO 8601 text with an "
                + "offset can order versions");
        assertThat(OrderColumn.problem("connector.v_x", "occurred_at", "text")).isNull();
    }

    /** #1816: a hybrid table has no {@code _ingested_at}; its {@code indexed_at} is when the row last changed. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ext.t         | indexed_at    | \"ext\".\"t\".indexed_at DESC, \"ext\".\"t\".id DESC                   | _ingested_at",
        "connector.t_h | indexed_at    | \"connector\".\"t_h\".indexed_at DESC, \"connector\".\"t_h\".id DESC     | _ingested_at",
        "connector.t_x | _ingested_at  | \"connector\".\"t_x\"._ingested_at DESC, \"connector\".\"t_x\".id DESC | indexed_at"})
    void versions_tie_break_by_when_the_row_was_written(String table, String writtenAt, String tail, String absent) {
        String plain = OrderColumn.currentOnTable(null, null, "\"k\"", "\"k\" AS k", table, writtenAt, "");
        String ordered = OrderColumn.currentOnTable(OrderColumn.INTEGER, "\"n\"", "\"k\"", "\"k\" AS k", table, writtenAt, "");
        assertThat(plain).endsWith(tail).doesNotContain(absent);
        assertThat(ordered).endsWith(tail).doesNotContain(absent);
    }

    @Test
    void a_typed_column_orders_as_itself() {
        assertThat(OrderColumn.INTEGER.expression("\"at\"")).isEqualTo("\"at\"");
        assertThat(OrderColumn.TEXT.expression("\"at\"")).contains("pg_input_is_valid(\"at\"::text, 'timestamptz')");
    }
}
