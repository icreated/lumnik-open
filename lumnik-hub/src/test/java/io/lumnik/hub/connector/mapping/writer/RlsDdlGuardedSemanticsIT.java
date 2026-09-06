package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why every grant gets its own guarded block — asserted against a real Postgres, not asserted in
 * prose.
 *
 * <p>{@link RlsDdl#guarded} ends {@code EXCEPTION WHEN OTHERS THEN NULL}. In PL/pgSQL an exception
 * handler <b>abandons its block</b>: statements after the one that raised are never reached, and
 * the handler then swallows the error, so nothing anywhere says so. Grouping grants therefore made
 * them share a fate — and failing is the ordinary case, not the remote one, since
 * {@code guarded}'s own javadoc notes the target roles may not exist.
 *
 * <p>The doomed statement here grants to a role that does not exist, which is precisely that
 * ordinary case. The observable is a marker table: created inside the same block, it exists only
 * if the block reached it.
 */
@QuarkusTest
class RlsDdlGuardedSemanticsIT {

    private static final String DOOMED =
        "GRANT SELECT ON pg_catalog.pg_class TO no_such_role_rls_probe";
    private static final String GROUPED_MARKER = "connector.rls_probe_grouped";
    private static final String SPLIT_MARKER = "connector.rls_probe_split";

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    @AfterEach
    void dropMarkers() {
        execSql(adminDs, "DROP TABLE IF EXISTS " + GROUPED_MARKER);
        execSql(adminDs, "DROP TABLE IF EXISTS " + SPLIT_MARKER);
    }

    @Test
    void a_failing_statement_cancels_everything_after_it_in_the_same_block() {
        execSql(adminDs, grouped(DOOMED, "CREATE TABLE " + GROUPED_MARKER + " (id int)"));

        assertThat(tableExists(GROUPED_MARKER))
            .as("the statement after a failure is never reached — and the swallow means no error "
                + "reports it; this is what grouped grants cost")
            .isFalse();
    }

    @Test
    void separate_blocks_do_not_share_a_fate() {
        execSql(adminDs, RlsDdl.guarded(DOOMED));
        execSql(adminDs, RlsDdl.guarded("CREATE TABLE " + SPLIT_MARKER + " (id int)"));

        assertThat(tableExists(SPLIT_MARKER))
            .as("one block per statement: the doomed one is still swallowed, the next still runs")
            .isTrue();
    }

    /**
     * The grouped shape, hand-built — {@link RlsDdl#guarded} takes a single statement now and can
     * no longer express it, which is the point. What this test documents is a PostgreSQL guarantee
     * the production code leans on, not a contract of {@code RlsDdl}.
     */
    private static String grouped(String... statements) {
        StringBuilder sb = new StringBuilder("DO $lumnik_rls$ BEGIN ");
        for (String statement : statements) sb.append("  ").append(statement).append("; ");
        return sb.append("EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_rls$").toString();
    }

    private boolean tableExists(String qualified) {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('" + qualified + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        } catch (Exception e) {
            throw new IllegalStateException("checking " + qualified, e);
        }
    }
}
