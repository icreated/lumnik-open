package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #1311: two drains that overlap, a second hub replica or a direct call next to the scheduled one,
 * must copy an outbox row into the trail once. The other drain is played by a second connection
 * that has copied and marked the row but not committed yet: the drain under test must not copy it
 * again once that one commits.
 */
@QuarkusTest
class AuditDispatcherOverlapIT {

  private static final long TENANT = 4344L;

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject AuditDispatcher dispatcher;
  @Inject Scheduler scheduler;

  @Test
  void a_row_another_drain_is_dispatching_is_not_copied_twice() throws Exception {
    scheduler.pause();   // only the two drains of this test run
    try {
      String key = "overlap-" + System.nanoTime();
      long id = landInOutbox(key);
      try (Connection other = adminDs.getConnection()) {
        other.setAutoCommit(false);
        copyAndMark(other, id);

        CompletableFuture<Void> drain = CompletableFuture.runAsync(dispatcher::drain);
        // Committing before the drain read the row would make the test pass whatever the claim.
        await().atMost(30, SECONDS).pollInterval(20, MILLISECONDS)
            .until(() -> drain.isDone() || blocksADrain(other));
        other.commit();
        drain.get(30, SECONDS);
      }
      assertThat(count(key)).as("the outbox row reaches the trail once").isEqualTo(1L);
    } finally {
      scheduler.resume();
    }
  }

  private long landInOutbox(String key) throws SQLException {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO core.audit_outbox "
           + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
           + "VALUES (?, 1, 'Probe', ?, 'CREATE', '{}'::jsonb, now()) RETURNING id")) {
      ps.setLong(1, TENANT);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** What one drain does to one row, left uncommitted: the copy, then the mark that locks the row. */
  private static void copyAndMark(Connection c, long id) throws SQLException {
    try (PreparedStatement copy = c.prepareStatement(
             "INSERT INTO core.audit_event "
           + "(tenant_id, occurred_at, actor_id, entity_type, entity_key, operation, changes) "
           + "SELECT tenant_id, created_at, actor_id, entity_type, entity_key, operation, changes "
           + "FROM core.audit_outbox WHERE id = ? AND dispatched = false");
         PreparedStatement mark = c.prepareStatement(
             "UPDATE core.audit_outbox SET dispatched = true WHERE id = ?")) {
      copy.setLong(1, id);
      assertThat(copy.executeUpdate()).as("the row is still pending when the other drain takes it").isEqualTo(1);
      mark.setLong(1, id);
      mark.executeUpdate();
    }
  }

  /** Whether a backend now waits on a lock {@code holder} holds: the drain, reaching the row. */
  private static boolean blocksADrain(Connection holder) throws SQLException {
    try (PreparedStatement ps = holder.prepareStatement(
             "SELECT count(*) FROM pg_locks WHERE NOT granted AND pg_backend_pid() = ANY(pg_blocking_pids(pid))");
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1) > 0;
    }
  }

  private long count(String key) {
    return Jdbc.queryOne(adminDs, "count", "SELECT count(*) FROM core.audit_event WHERE entity_key = ?",
        ps -> ps.setString(1, key), rs -> rs.getLong(1)).orElseThrow();
  }
}
