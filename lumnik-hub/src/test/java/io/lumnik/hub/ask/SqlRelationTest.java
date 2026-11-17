package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #1978: the one reader of {@code schema.table [[AS] alias]} the ask guards share. */
class SqlRelationTest {

    /** The relation right after the leading FROM. */
    private static SqlRelation read(String from) {
        return SqlRelation.at(SqlLexer.tokenize(from), 1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
        FROM connector.v_order                       | -     | 4
        FROM connector.v_order AS o WHERE o.x = 1    | o     | 6
        FROM connector.v_order o WHERE o.x = 1       | o     | 5
        FROM Connector.V_Order AS O                  | o     | 6
        FROM connector.v_order "Where" WHERE true    | Where | 5
        FROM connector.v_order AS "O"                | O     | 6
        FROM "connector"."v_order" AS "o"            | o     | 6
        FROM connector.v_order WHERE true            | -     | 4
        FROM connector.v_order TABLESAMPLE SYSTEM(1) | -     | 4
        FROM connector.v_order AS 5                  | -     | 4
        FROM connector.v_order JOIN connector.t_a    | -     | 4
        FROM connector.v_order LEFT JOIN connector.t | -     | 4
        FROM connector.v_order ORDER BY 1            | -     | 4
        FROM connector.v_order, connector.t_a        | -     | 4
        """)
    void aRelationIsItsSchemaItsTableAndItsAlias(String from, String alias, int next) {
        SqlRelation r = read(from);
        assertThat(r.fqn()).isEqualTo("connector.v_order");
        assertThat(r.alias()).isEqualTo(alias);
        assertThat(r.qualifier()).isEqualTo(alias == null ? "v_order" : alias);
        assertThat(r.next()).isEqualTo(next);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FROM v_order WHERE true",
        "FROM (SELECT 1) s",
        "FROM generate_series(1, 3) g",
        "FROM connector.v_order AS s(a, b)",
        "FROM connector.v_order s(a, b)",
        "FROM connector.",
        "FROM"})
    void anythingElseIsNotRead(String from) {
        assertThat(read(from)).isNull();
    }
}
