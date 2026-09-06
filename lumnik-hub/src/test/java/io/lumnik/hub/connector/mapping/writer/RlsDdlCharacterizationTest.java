package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
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
 * Drift guard over the tenant-isolation DDL emitted by BOTH dynamic writers.
 *
 * <p>The {@code tenant_isolation} policy and the role grants used to be written out twice, once per
 * writer — and the V21/V31 policy-shape fix had to be applied to both copies by hand. The DDL now
 * comes from {@link RlsDdl}; these expectations are flat literals (deliberately NOT rebuilt with the
 * production concatenation) so a change in the emitter shows up here as a diff, on both writers.
 *
 * <p>This is tenant-isolation security DDL: a divergence between the two writers is an isolation
 * bug, not a style nit.
 */
class RlsDdlCharacterizationTest {

    private static final String POLICY_ON_CONNECTOR_TABLE =
        "DO $lumnik_rls$ BEGIN   IF NOT EXISTS (    SELECT 1 FROM pg_policy     WHERE polname = 'tenant_isolation' "
        + "AND polrelid = '\"connector\".\"t_orders\"'::regclass  ) THEN     "
        + "CREATE POLICY tenant_isolation ON \"connector\".\"t_orders\"      "
        + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);   END IF; END $lumnik_rls$";

    private static final String POLICY_ON_EXT_TABLE =
        "DO $lumnik_rls$ BEGIN   IF NOT EXISTS (    SELECT 1 FROM pg_policy     WHERE polname = 'tenant_isolation' "
        + "AND polrelid = '\"ext\".\"stripe_charges\"'::regclass  ) THEN     "
        + "CREATE POLICY tenant_isolation ON \"ext\".\"stripe_charges\"      "
        + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);   END IF; END $lumnik_rls$";

    @Test
    void dynamic_table_writer_emits_the_tenant_isolation_ddl() throws SQLException {
        DynamicTableWriter writer = new DynamicTableWriter();
        Statement st = mock(Statement.class);
        Connection c = mock(Connection.class);
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(c.createStatement()).thenReturn(st);
        when(ds.getConnection()).thenReturn(c);
        writer.adminDs = ds;

        writer.enableRLS(new MappingTarget.Table("connector", "t_orders", true,
                                                 MappingTarget.OnConflict.SKIP, List.of()));

        assertThat(executed(st)).containsExactly(
            "ALTER TABLE \"connector\".\"t_orders\" ENABLE ROW LEVEL SECURITY",
            POLICY_ON_CONNECTOR_TABLE,
            "CREATE INDEX IF NOT EXISTS \"t_orders_idx_tenant_id\" ON \"connector\".\"t_orders\" (tenant_id)",
            "DO $lumnik_rls$ BEGIN   GRANT INSERT, SELECT, UPDATE, DELETE ON \"connector\".\"t_orders\" TO lumnik_app; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$",
            "DO $lumnik_rls$ BEGIN   GRANT SELECT ON \"connector\".\"t_orders\" TO lumnik_readonly; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$",
            "DO $lumnik_rls$ BEGIN   GRANT USAGE, SELECT ON SEQUENCE \"connector\".\"t_orders_id_seq\" TO lumnik_app; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$");
    }

    /**
     * Every RLS-enforced read carries an implicit {@code tenant_id = ?} predicate (the policy above),
     * so a freshly created {@code connector.t_*} table needs a {@code tenant_id}-leading index or
     * every such read sequentially scans it. {@link HybridJsonbWriter}'s {@code ext.*} tables already
     * get one for free from their {@code UNIQUE (tenant_id, external_id)} constraint — this is only
     * for {@link DynamicTableWriter}.
     */
    @Test
    void dynamic_table_writer_emits_a_tenant_id_index() throws SQLException {
        DynamicTableWriter writer = new DynamicTableWriter();
        Statement st = mock(Statement.class);
        Connection c = mock(Connection.class);
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(c.createStatement()).thenReturn(st);
        when(ds.getConnection()).thenReturn(c);
        writer.adminDs = ds;

        writer.enableRLS(new MappingTarget.Table("connector", "t_orders", true,
                                                 MappingTarget.OnConflict.SKIP, List.of()));

        assertThat(executed(st)).contains(
            "CREATE INDEX IF NOT EXISTS \"t_orders_idx_tenant_id\" ON \"connector\".\"t_orders\" (tenant_id)");
    }

    /**
     * The hybrid writer emits the same policy for its {@code ext.*} tables, grants the same set as
     * {@link DynamicTableWriter} — including sequence usage — and now, like it, gives each grant
     * its OWN guarded block.
     */
    @Test
    void hybrid_jsonb_writer_emits_the_same_tenant_isolation_ddl() throws SQLException {
        assertThat(hybridStatements()).containsSubsequence(
            "ALTER TABLE \"ext\".\"stripe_charges\" ENABLE ROW LEVEL SECURITY",
            POLICY_ON_EXT_TABLE,
            "DO $lumnik_rls$ BEGIN   GRANT INSERT, SELECT, UPDATE, DELETE ON \"ext\".\"stripe_charges\" TO lumnik_app; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$",
            "DO $lumnik_rls$ BEGIN   GRANT SELECT ON \"ext\".\"stripe_charges\" TO lumnik_readonly; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$",
            "DO $lumnik_rls$ BEGIN   GRANT USAGE, SELECT ON SEQUENCE \"ext\".\"stripe_charges_id_seq\" TO lumnik_app; "
            + "EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$");
    }

    /**
     * The rule the literals above are one instance of: <b>one grant per guarded block</b>.
     *
     * <p>{@code EXCEPTION WHEN OTHERS} aborts its block, so a grant that fails takes every grant
     * after it in the same block down with it — silently, since the handler swallows. That is not
     * a remote possibility: {@code RlsDdl.guarded}'s own javadoc notes the target roles may not
     * exist at all. Grouping put {@code lumnik_readonly}'s SELECT second behind
     * {@code lumnik_app}'s DML, and the schema USAGE for readonly second behind the app's, so one
     * missing role silently cost ask-to-SQL both of its grants — on a table the schema card
     * advertises as askable.
     *
     * <p>Asserted as a property rather than as more literals, so a grant added later is covered
     * without anyone remembering this file exists.
     */
    @Test
    void every_grant_rides_its_own_guarded_block() throws SQLException {
        for (String sql : hybridStatements()) {
            if (!sql.contains("GRANT ")) continue;
            assertThat(sql)
                .as("one grant per guarded block, else a failing sibling cancels the rest")
                .containsOnlyOnce("GRANT ");
        }
    }

    private static List<String> hybridStatements() throws SQLException {
        HybridJsonbWriter writer = new HybridJsonbWriter();
        Statement st = mock(Statement.class);
        Connection c = mock(Connection.class);
        when(c.createStatement()).thenReturn(st);

        writer.createTable(c, new HybridJsonbSpec("ext.stripe_charges", "$.id", List.of()));
        return executed(st);
    }

    /** Both writers must emit a byte-identical policy statement modulo the table reference. */
    @Test
    void the_policy_statement_is_identical_across_both_writers() {
        assertThat(POLICY_ON_EXT_TABLE)
            .isEqualTo(POLICY_ON_CONNECTOR_TABLE
                           .replace("\"connector\".\"t_orders\"", "\"ext\".\"stripe_charges\""));
    }

    private static List<String> executed(Statement st) throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(st, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }
}
