package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specifies {@link EventItSupport}: the shared tx-with-rollback-on-throw guard, admin-DS SQL,
 * and webhook seeding used across the event/webhook/kafka ITs. The central case is the ARJUNA
 * guard — a throwing body must leave NO poisoned transaction associated with the thread, so a
 * subsequent {@code runInTxAsSystem} on the same {@code utx} still succeeds.
 */
@QuarkusTest
class EventItSupportTest {

    private static final String TMP_TABLE = "core.eis_test_tmp";
    private static final String ENDPOINT_NAME = "eis-test-endpoint";

    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void setup() {
        cleanup();
        EventItSupport.execSql(adminDs, "CREATE TABLE IF NOT EXISTS " + TMP_TABLE + " (v BIGINT)");
        EventItSupport.execSql(adminDs, "DELETE FROM " + TMP_TABLE);
    }

    @AfterEach
    void teardown() {
        cleanup();
        EventItSupport.execSql(adminDs, "DROP TABLE IF EXISTS " + TMP_TABLE);
    }

    private void cleanup() {
        // Drop any delivery FK rows first, then the seeded endpoint.
        rawExec("DELETE FROM core.webhook_delivery WHERE endpoint_id IN "
            + "(SELECT id FROM core.webhook_endpoint WHERE name = '" + ENDPOINT_NAME + "')");
        rawExec("DELETE FROM core.webhook_endpoint WHERE name = '" + ENDPOINT_NAME + "'");
    }

    private void rawExec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void runInTxAsSystem_commitsAndReturnsValue() {
        long result = EventItSupport.runInTxAsSystem(utx, () -> {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO " + TMP_TABLE + " (v) VALUES (42)");
            }
            return 42L;
        });

        assertThat(result).as("body's value is returned").isEqualTo(42L);
        assertThat(EventItSupport.scalar(adminDs, "SELECT v FROM " + TMP_TABLE))
            .as("the inserted row was committed and is visible after the tx")
            .isEqualTo(42L);
    }

    @Test
    void throwingBodyRethrows_andLeavesNoPoisonedTx() {
        // A throwing body must surface as RuntimeException AND roll back cleanly.
        assertThatThrownBy(() ->
            EventItSupport.runInTxAsSystem(utx, () -> { throw new IllegalStateException("boom"); }))
            .isInstanceOf(RuntimeException.class);

        // The ARJUNA guard: the SAME utx must be usable again — the rollback left no transaction
        // associated with the thread (otherwise utx.begin() throws "already associated").
        long after = EventItSupport.runInTxAsSystem(utx, () -> {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO " + TMP_TABLE + " (v) VALUES (7)");
            }
            return 7L;
        });

        assertThat(after).as("a subsequent tx on the same utx succeeds after the rollback").isEqualTo(7L);
        assertThat(EventItSupport.scalar(adminDs, "SELECT count(*) FROM " + TMP_TABLE))
            .as("only the second (committed) row exists — the thrown body rolled back").isEqualTo(1L);
    }

    @Test
    void seedWebhookEndpoint_insertsOneEnabledRow() {
        EventItSupport.seedWebhookEndpoint(adminDs, utx, ENDPOINT_NAME, "data.row.changed");

        assertThat(EventItSupport.scalar(adminDs,
            "SELECT count(*) FROM core.webhook_endpoint WHERE name = '" + ENDPOINT_NAME + "'"))
            .as("exactly one endpoint seeded under the given name").isEqualTo(1L);
    }
}
