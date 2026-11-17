package io.lumnik.hub.hook;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V94 (#2011) replayed over a table that still holds tenant scripts — an install where an lm_admin stored one before
 * the upgrade. The SHIPPED script runs over seeded rows in one transaction that is rolled back: every script is gone,
 * every toggle stays as it was, and a script written afterwards — by a node still on the old code, during a rolling
 * upgrade — is refused by the database itself.
 *
 * <p>Flyway applied V94 at startup, so its constraint is lifted first, inside the same rolled-back transaction, to
 * seed what an install held before it.
 */
@QuarkusTest
class HookScriptsClearedMigrationIT {

    private static final String SCRIPT = "/db/migration/core/V94__hook_override_scripts_cleared.sql";
    private static final long TENANT = 20111L;
    private static final long OTHER_TENANT = 20112L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void everyStoredScriptIsCleared_andTheTogglesStay() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE core.hook_tenant_override DROP CONSTRAINT hook_tenant_override_carries_no_script");
                }
                seed(c, TENANT, "h.with-script", false, "java.lang.System.getenv()");
                seed(c, TENANT, "h.toggle-only", false, null);
                seed(c, OTHER_TENANT, "h.with-script", true, "println(1)");

                try (Statement st = c.createStatement()) {
                    st.execute(script());
                }

                assertThat(rows(c, "SELECT tenant_id || ':' || hook_id || ':' || enabled || ':' || (script_content IS NULL) "
                        + "FROM core.hook_tenant_override WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ") ORDER BY 1"))
                    .containsExactly(TENANT + ":h.toggle-only:false:true", TENANT + ":h.with-script:false:true",
                        OTHER_TENANT + ":h.with-script:true:true");
                assertThat(rows(c, "SELECT count(*) FROM core.hook_tenant_override WHERE script_content IS NOT NULL"))
                    .as("no script is left, whichever tenant stored it").containsExactly("0");

                // an old node, after the migration: neither a new script nor a script written over a toggle is stored
                refused(c, () -> seed(c, TENANT, "h.new", true, "println(2)"));
                refused(c, () -> Jdbc.update(c, "HookScriptsClearedMigrationIT.rewrite",
                    "UPDATE core.hook_tenant_override SET script_content = 'println(3)' WHERE tenant_id = ?",
                    ps -> ps.setLong(1, TENANT)));
            } finally {
                c.rollback();
            }
        }
    }

    private static void seed(Connection c, long tenant, String hookId, boolean enabled, String script) {
        Jdbc.update(c, "HookScriptsClearedMigrationIT.seed", "INSERT INTO core.hook_tenant_override "
            + "(tenant_id, hook_id, enabled, script_content, updated_at) VALUES (?, ?, ?, ?, now())", ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, hookId);
                ps.setBoolean(3, enabled);
                ps.setString(4, script);
            });
    }

    /** {@code write} fails on the check constraint (SQLSTATE 23514); the transaction carries on past it (a savepoint). */
    private static void refused(Connection c, Runnable write) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            assertThatThrownBy(write::run).satisfies(e -> assertThat(sqlStateOf(e)).isEqualTo("23514"));
        } finally {
            c.rollback(sp);
        }
    }

    private static String sqlStateOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    private static List<String> rows(Connection c, String sql) {
        return Jdbc.query(c, "HookScriptsClearedMigrationIT.rows", sql, ps -> { }, rs -> rs.getString(1));
    }

    private static String script() throws Exception {
        try (InputStream in = HookScriptsClearedMigrationIT.class.getResourceAsStream(SCRIPT)) {
            assertThat(in).as(SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
