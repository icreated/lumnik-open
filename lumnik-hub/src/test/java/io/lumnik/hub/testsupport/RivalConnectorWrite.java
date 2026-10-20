package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.repository.ConnectorRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A writer that has claimed a free connector name the way the doors claim it — the address's
 * advisory lock, then the row — and has not committed (#1231). {@link #answerAfterCommit} starts a
 * door, proves it waits for this write, commits, and returns the door's answer: the door is judged
 * on the holder this write commits.
 */
public final class RivalConnectorWrite implements AutoCloseable {

  private final Connection c;

  /** Tenant 1's connector {@code id} under {@code name}, carrying {@code tags}, held uncommitted. */
  public RivalConnectorWrite(AgroalDataSource adminDs, UUID id, String name, String... tags) throws SQLException {
    c = adminDs.getConnection();
    c.setAutoCommit(false);
    try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, ConnectorRepository.addressLockKey(1, name));
      lock.execute();
    }
    try (PreparedStatement insert = c.prepareStatement("INSERT INTO connector.connector"
        + " (id, tenant_id, name, type_id, config, enabled, tags) VALUES (?, 1, ?, 'csv-file', '{}'::jsonb, true, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, name);
      insert.setArray(3, c.createArrayOf("text", tags));
      insert.execute();
    }
  }

  public <T> T answerAfterCommit(Supplier<T> door) throws Exception {
    CompletableFuture<T> request = CompletableFuture.supplyAsync(door);
    awaitAWaiter(request);
    c.commit();
    return request.get(30, TimeUnit.SECONDS);
  }

  /**
   * Until a backend is blocked by THIS write — the door reaching the name it holds. Any lock wait
   * would do for a wait elsewhere in the hub, and the rival would commit before the door got here.
   * Not only advisory waits: without the address lock, the door waits on this row's unique key.
   */
  private void awaitAWaiter(CompletableFuture<?> request) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline && !request.isDone()) {
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_locks"
               + " WHERE NOT granted AND pg_backend_pid() = ANY(pg_blocking_pids(pid))")) {
        rs.next();
        if (rs.getLong(1) > 0) return;
      }
      Thread.sleep(20);
    }
    assertThat(request).as("the door waits for the rival write in flight").isNotDone();
    throw new AssertionError("the door never waited on the rival write");
  }

  @Override
  public void close() throws SQLException {
    if (!c.getAutoCommit()) c.rollback();   // a no-op once committed
    c.close();
  }
}
