package io.lumnik.hub.testsupport;

import io.lumnik.common.sql.Jdbc;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Waiting, in a test, for a session to wait on another's locks: the proof that a lock serialises two steps. */
public final class LockWaits {

  private LockWaits() {
  }

  /** The session waiting on {@code holder}'s locks, once there is one; 30 seconds at most. */
  public static int awaitBlockedBy(DataSource ds, Connection holder) throws InterruptedException {
    int pid = Jdbc.queryOne(holder, "pid", "SELECT pg_backend_pid()", ps -> {}, rs -> rs.getInt(1)).orElseThrow();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      Optional<Integer> waiting = Jdbc.queryOne(ds, "blocked", "SELECT pid FROM pg_locks "
          + "WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid)) LIMIT 1", ps -> ps.setInt(1, pid), rs -> rs.getInt(1));
      if (waiting.isPresent()) return waiting.get();
      if (System.nanoTime() > deadline) throw new AssertionError("no session waited on the holder");
      Thread.sleep(50);
    }
  }
}
