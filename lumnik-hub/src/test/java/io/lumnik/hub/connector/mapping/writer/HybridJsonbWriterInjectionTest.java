package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HybridJsonbWriter#createTable} used to hand-roll its OWN {@code DO $$ ... $$} block for
 * the schema-USAGE grants, separate from {@link RlsDdl} — with the same bare-{@code $$} exposure
 * {@link RlsDdlInjectionTest} proved for {@code RlsDdl.guarded}. The schema name comes straight
 * from a REST/hybrid connector manifest's {@code target.table} string
 * ({@code RestEndpointWriter.parseHybridSpec}), unvalidated.
 *
 * <p>The fix routes this grant block through {@link RlsDdl#guarded} instead of duplicating the
 * tag logic — this test proves that routing happened by comparing against the exact SQL
 * {@code RlsDdl.guarded} itself produces (already proven injection-safe by
 * {@link RlsDdlInjectionTest}), not by re-deriving the tag by hand.
 */
class HybridJsonbWriterInjectionTest {

    private static final String TAG = "$lumnik_rls$";

    @Test
    void schema_usage_grant_is_routed_through_RlsDdl_guarded_and_the_dollar_tag_survives_a_hostile_schema_name()
            throws SQLException {
        HybridJsonbWriter writer = new HybridJsonbWriter();
        Statement st = mock(Statement.class);
        Connection c = mock(Connection.class);
        when(c.createStatement()).thenReturn(st);

        String schema = "s$$evil";
        writer.createTable(c, new HybridJsonbSpec(schema + ".t", "$.id", List.of()));

        List<String> executed = executed(st);
        String expectedGrantBlock = RlsDdl.guarded(
            RlsDdl.grantSchemaUsageApp(schema),
            RlsDdl.grantSchemaUsageReadonly(schema));

        assertThat(executed).contains(expectedGrantBlock);

        // The schema's own embedded "$$" must not forge a second matching pair of the chosen tag —
        // it appears exactly twice (open + close), same proof shape as RlsDdlInjectionTest.
        assertThat(countOccurrences(expectedGrantBlock, TAG)).isEqualTo(2);
    }

    private static List<String> executed(Statement st) throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(st, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}
