package io.lumnik.hub.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlIdentifiersTest {

    @Test
    void quoteIdent_wraps_and_doubles_embedded_quotes() {
        assertThat(SqlIdentifiers.quoteIdent("name")).isEqualTo("\"name\"");
        // The injection defence: an embedded double-quote is doubled, not escaped away.
        assertThat(SqlIdentifiers.quoteIdent("a\"b")).isEqualTo("\"a\"\"b\"");
        // A hyphenated identifier is valid Postgres and must survive (quoting, not allowlisting).
        assertThat(SqlIdentifiers.quoteIdent("t-cust")).isEqualTo("\"t-cust\"");
        // Same for a name with a space, which the writer can and does create.
        assertThat(SqlIdentifiers.quoteIdent("a b")).isEqualTo("\"a b\"");
        assertThat(SqlIdentifiers.quoteIdent("we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(SqlIdentifiers.quoteIdent("t\";DROP")).isEqualTo("\"t\"\";DROP\"");
    }

    @Test
    void quoteTable_quotes_both_parts_of_a_qualified_name() {
        assertThat(SqlIdentifiers.quoteTable("connector.v_customer"))
            .isEqualTo("\"connector\".\"v_customer\"");
        assertThat(SqlIdentifiers.quoteTable("connector.t_x"))
            .isEqualTo("\"connector\".\"t_x\"");
    }

    @Test
    void quoteTable_matches_the_split_limit_two_idiom_the_writers_used() {
        // Pins the equivalence the dynamic-table writers relied on before they delegated here:
        // splitting on the first dot only, so a dot in the table part stays in the second segment.
        for (String fq : new String[]{"connector.t_x", "t_x", "a.b.c", "connector.t-my table"}) {
            String[] parts = fq.split("\\.", 2);
            String expected = parts.length == 2
                ? SqlIdentifiers.quoteIdent(parts[0]) + "." + SqlIdentifiers.quoteIdent(parts[1])
                : SqlIdentifiers.quoteIdent(parts[0]);
            assertThat(SqlIdentifiers.quoteTable(fq)).isEqualTo(expected);
        }
    }

    @Test
    void quoteTable_quotes_a_bare_name_whole() {
        assertThat(SqlIdentifiers.quoteTable("v_customer")).isEqualTo("\"v_customer\"");
    }

    @Test
    void quoteTable_splits_on_the_first_dot_only() {
        // A dot in the table part stays inside the quoted second segment.
        assertThat(SqlIdentifiers.quoteTable("schema.a.b")).isEqualTo("\"schema\".\"a.b\"");
    }
}
