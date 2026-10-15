package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The fixture's DDL ({@code DROP TABLE} needs an exclusive lock) must never wait on a lock another
 * session holds for longer than a short bound: a DDL setup once waited for the full 1800 s snapshot
 * timeout and stalled the nightly for half an hour (#755). A leaked snapshot-read transaction is the
 * leading hypothesis, not an established cause. Bounded test-harness containment — it limits what a
 * stall costs, it does not explain the stall (#761).
 */
@QuarkusTest
class EntitySourceTableFixtureIT {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String TABLE = "t_fixturelock_erp";
    private static final String VIEW = "v_fixturelock";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void setupFailsPromptlyWithLockNotAvailableWhileAnotherSessionHoldsTheTable() throws Exception {
        EntitySourceTableFixture.create(adminDs, TABLE, VIEW, "cust_id");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("LOCK TABLE connector." + TABLE + " IN ACCESS SHARE MODE");
            }
            Future<?> setup = pool.submit(() -> {
                EntitySourceTableFixture.create(adminDs, TABLE, VIEW, "cust_id");
                return null;
            });
            try {
                setup.get(30, TimeUnit.SECONDS);
                fail("setup returned while another session still held the table");
            } catch (TimeoutException e) {
                fail("setup was still waiting after 30 s on a table another session holds: no lock bound");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(SQLException.class);
                assertThat(((SQLException) e.getCause()).getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE);
            } finally {
                holder.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLockBoundDoesNotOutliveTheSetupOnThePooledConnection() throws Exception {
        EntitySourceTableFixture.create(adminDs, TABLE, VIEW, "cust_id");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW lock_timeout")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).as("lock_timeout on a pooled admin connection").isEqualTo("0");
            assertThat(c.getAutoCommit()).as("autocommit on a pooled admin connection").isTrue();
        }
    }
}
