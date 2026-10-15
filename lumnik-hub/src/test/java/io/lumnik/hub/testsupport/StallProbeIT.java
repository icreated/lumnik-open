package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link StallProbe} must show when a session waits on another: who waits, who holds, on what.
 * The nightly stall of {@code ExportAcceptanceS3IT} (#761) left no database evidence; the probe is
 * only worth having if it names the blocker when there is one and invents none when there is not.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class StallProbeIT {

    private static final String TABLE = "t_stallprobe_erp";
    private static final String VIEW = "v_stallprobe";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void namesTheSessionThatWaitsTheSessionThatHoldsAndTheTable() throws Exception {
        EntitySourceTableFixture.create(adminDs, TABLE, VIEW, "cust_id");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = adminDs.getConnection(); Connection waiter = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            long holderPid = pid(holder);
            long waiterPid = pid(waiter);
            try (Statement st = holder.createStatement()) {
                st.execute("LOCK TABLE connector." + TABLE + " IN ACCESS SHARE MODE");
            }
            Future<?> blocked = pool.submit(() -> {
                try (Statement st = waiter.createStatement()) {
                    st.execute("SET lock_timeout = '30s'");
                    st.execute("DROP TABLE connector." + TABLE);
                } catch (Exception e) {
                    return e; // the release below cancels nothing; the holder's rollback frees it
                }
                return null;
            });
            awaitWaiting(waiterPid);

            String report = StallProbe.report();

            assertThat(report).as("the waiting session").contains("pid=" + waiterPid);
            assertThat(report).as("the holding session").contains("pid=" + holderPid);
            assertThat(report).as("the waiter's blocker, by pid").contains("blocked_by=[" + holderPid + "]");
            assertThat(report).as("the contended table").contains("connector." + TABLE);
            assertThat(report).as("the lock that is not granted").contains("granted=false");

            holder.rollback();
            blocked.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            EntitySourceTableFixture.create(adminDs, TABLE, VIEW, "cust_id");
        }
    }

    @Test
    void inventsNoBlockerWhenNobodyWaits() throws Exception {
        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            long holderPid = pid(holder);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT 1");
            }

            String report = StallProbe.report();

            // The probe is not deaf: it sees the open transaction ...
            assertThat(report).contains("pid=" + holderPid).contains("idle in transaction");
            // ... and, with nobody waiting, claims no blocker and no ungranted lock.
            assertThat(report).doesNotContain("granted=false").doesNotContainPattern("blocked_by=\\[\\d");
            holder.rollback();
        }
    }

    private static long pid(Connection c) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void awaitWaiting(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 FROM pg_stat_activity WHERE pid = " + pid
                     + " AND wait_event_type = 'Lock'")) {
                if (rs.next()) return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the session never started waiting on a lock");
    }
}
