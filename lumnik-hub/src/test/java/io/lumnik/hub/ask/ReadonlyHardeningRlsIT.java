package io.lumnik.hub.ask;

import static io.lumnik.hub.connector.rls.RlsEnforcedPgResource.asSuperuser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskUnavailableException;
import io.lumnik.extraction.spi.ask.NoSchemaException;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #1987 against the real {@code lumnik_readonly}, with the shipped {@code 02-extensions.sql} applied by the harness:
 * the two statements of the audit fail on the revoked function instead of switching tenant, the platform tables are
 * not readable, the check passes — and the moment the role gets {@code set_config} back, Ask closes.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ReadonlyHardeningRlsIT {

    @Inject AskToSqlAgent agent;
    @Inject ReadonlyHardening hardening;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock @Analytic ChatPort chatPort;

    static final String SET_CONFIG = "pg_catalog.set_config(text,text,boolean)";

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector.t_hardening");
            st.execute("CREATE TABLE connector.t_hardening (tenant_id bigint NOT NULL, v int)");
            st.execute("INSERT INTO connector.t_hardening VALUES (1, 1), (1, 2), (11, 3)");
            st.execute("ALTER TABLE connector.t_hardening ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE connector.t_hardening FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON connector.t_hardening"
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            // explicit, as the hub's writers do: no default privilege hands a new table to the readonly role (V93)
            st.execute("GRANT SELECT ON connector.t_hardening TO lumnik_readonly");
        }
    }

    @Test
    void the_shipped_file_leaves_the_readonly_role_nothing_the_check_looks_for() throws SQLException {
        assertThat(asReadonly("SELECT current_user")).isEqualTo("lumnik_readonly");
        assertThat(hardening.stillOpen()).isEmpty();
        assertThat(asReadonly("SELECT count(*) FROM connector.t_hardening")).as("tenant 1's rows only").isEqualTo("2");
    }

    @Test
    void the_tenant_switch_of_the_audit_fails_on_set_config() {
        assertThatThrownBy(() -> asReadonly(
                "WITH s AS MATERIALIZED (SELECT set_config('app.current_tenant','11',true) AS x) "
                    + "SELECT count(*) AS n FROM s, connector.t_hardening w WHERE s.x IS NOT NULL"))
            .hasMessageContaining("permission denied for function set_config");
    }

    @Test
    void the_query_held_in_a_string_fails_on_query_to_xml() {
        assertThatThrownBy(() -> asReadonly(
                "SELECT query_to_xml('select count(*) from connector.connector', false, false, '')::text"))
            .hasMessageContaining("permission denied for function query_to_xml");
    }

    @Test
    void the_session_lock_and_the_sessions_in_flight_are_refused() {
        assertThatThrownBy(() -> asReadonly("SELECT pg_advisory_lock(hashtextextended('connector.t_hardening', 0))"))
            .hasMessageContaining("permission denied for function pg_advisory_lock");
        assertThatThrownBy(() -> asReadonly("SELECT count(*) FROM pg_stat_activity"))
            .hasMessageContaining("permission denied for view pg_stat_activity");
        assertThatThrownBy(() -> asReadonly(
                "SELECT count(pg_stat_get_backend_activity(id)) FROM pg_stat_get_backend_idset() id"))
            .hasMessageContaining("permission denied for function pg_stat_get_backend_idset");
        assertThatThrownBy(() -> asReadonly(
                "SELECT ts_rewrite('a'::tsquery, 'SELECT to_tsquery(''b''), ''a''::tsquery FROM connector.t_hardening')::text"))
            .hasMessageContaining("permission denied for function ts_rewrite");
    }

    /** #2090: the server stops a model's query by itself, not only the client timer; the transaction keeps it. */
    @Test
    void the_server_holds_the_ask_timeout() {
        var read = QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                return AskToSqlAgent.select(c, "SELECT current_setting('statement_timeout') AS t");
            }
        });
        assertThat(read).containsExactly(Map.of("t", AskToSqlAgent.TIMEOUT_S + "s"));
    }

    /** #2090: a wait, a message to another session and a large object are refused by the role, not by a name list. */
    @Test
    void the_sleep_the_notify_and_the_large_object_read_are_refused() {
        assertThatThrownBy(() -> asReadonly("SELECT pg_sleep(0)::text"))
            .hasMessageContaining("permission denied for function pg_sleep");
        assertThatThrownBy(() -> asReadonly("SELECT pg_sleep_for('0 s')::text"))
            .hasMessageContaining("permission denied for function pg_sleep_for");
        assertThatThrownBy(() -> asReadonly("SELECT pg_notify('c', 'x')::text"))
            .hasMessageContaining("permission denied for function pg_notify");
        assertThatThrownBy(() -> asReadonly("SELECT lo_get(1)::text"))
            .hasMessageContaining("permission denied for function lo_get");
        assertThatThrownBy(() -> asReadonly("SELECT loread(lo_open(1, 262144), 10)::text"))
            .hasMessageContaining("permission denied for function lo");
    }

    @Test
    void a_readonly_role_that_bypasses_rls_closes_ask() throws SQLException {
        asSuperuser("ALTER ROLE lumnik_readonly BYPASSRLS");
        try {
            assertThat(hardening.stillOpen()).containsExactly("the role itself: superuser or BYPASSRLS");
        } finally {
            asSuperuser("ALTER ROLE lumnik_readonly NOBYPASSRLS");
        }
    }

    @Test
    void a_column_granted_back_on_a_platform_table_closes_ask() throws SQLException {
        asSuperuser("GRANT SELECT (config) ON connector.connector TO lumnik_readonly");
        try {
            assertThat(hardening.stillOpen()).containsExactly("connector.connector");
        } finally {
            asSuperuser("REVOKE SELECT (config) ON connector.connector FROM lumnik_readonly");
        }
    }

    @Test
    void the_platform_tables_are_not_readable() {
        for (String t : new String[] {"connector", "connector_credential", "connector_dead_letter",
                "connector_endpoint", "connector_run", "connector_cursor"}) {
            assertThatThrownBy(() -> asReadonly("SELECT count(*) FROM connector." + t))
                .as(t).hasMessageContaining("permission denied for table " + t);
        }
    }

    @Test
    void a_hardened_role_lets_ask_through_the_check() {
        assertThatThrownBy(() -> agent.ask(1L, "no-card-here", "How many?"))
            .as("past the check, the next door is the scope's card")
            .isInstanceOf(NoSchemaException.class);
    }

    @Test
    void set_config_given_back_to_the_readonly_role_closes_ask_before_any_model_call() throws SQLException {
        asSuperuser("GRANT EXECUTE ON FUNCTION " + SET_CONFIG + " TO lumnik_readonly");
        try {
            assertThat(hardening.stillOpen()).containsExactly("set_config(text,text,boolean)");

            assertThatThrownBy(() -> agent.ask(1L, "no-card-here", "How many?"))
                .isInstanceOf(AskUnavailableException.class);
            Mockito.verifyNoInteractions(chatPort);
        } finally {
            asSuperuser("REVOKE EXECUTE ON FUNCTION " + SET_CONFIG + " FROM lumnik_readonly");
        }
        assertThat(hardening.stillOpen()).as("revoked again, Ask reopens without a restart").isEmpty();
    }

    /**
     * #1988: a backslash in a plain string is data only under standard_conforming_strings=on, so the readonly
     * transaction pins it rather than trusting the server's default or the session's: here the transaction turns it
     * off first, and the binding every Ask read takes turns it back on.
     */
    @Test
    void the_readonly_transaction_reads_a_backslash_as_data_whatever_the_session_said() {
        String[] read = QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = readonlyDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("SET LOCAL standard_conforming_strings = off");
                TenantRlsScope.applyTo(c, 1L);
                try (ResultSet rs = st.executeQuery(
                        "SELECT current_setting('standard_conforming_strings'), length('a\\b')")) {
                    rs.next();
                    return new String[] {rs.getString(1), rs.getString(2)};
                }
            }
        });
        assertThat(read).containsExactly("on", "3");
    }

    /** One statement as the readonly role, under the binding every Ask read takes (tenant 1, empty search_path). */
    private String asReadonly(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }
}
