package io.lumnik.hub.run;

import io.agroal.api.AgroalDataSource;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A session-level Postgres advisory lock keyed by a UUID, held on a dedicated connection for as
 * long as the run it guards is alive. It serializes runs of one key across every replica. If a pod
 * dies, its backend session ends and Postgres releases the lock on its own, so a lock nobody holds
 * is also how the reaper knows a {@code Running} row has no live owner: it takes the lock with
 * {@link #tryAcquire} and keeps it while it fails the row.
 *
 * <p>{@link #close()} unlocks BEFORE it returns the connection to the pool. Returned still locked,
 * the lock would leak onto whichever caller next borrowed that backend session, so when the unlock
 * fails the session is closed instead of pooled.
 */
public final class RunLock implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(RunLock.class);

  private final Connection conn;
  private final long key;

  private RunLock(Connection conn, long key) {
    this.conn = conn;
    this.key = key;
  }

  /**
   * Takes the lock without blocking. Returns {@code null} when another session already holds it.
   * Throws when no connection could be had or the lock query failed; the connection is released
   * in both cases.
   */
  public static RunLock tryAcquire(AgroalDataSource adminDs, UUID id) throws SQLException {
    long key = key(id);
    Connection conn = adminDs.getConnection();
    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getBoolean(1)) return new RunLock(conn, key);
      }
    } catch (SQLException e) {
      closeQuietly(conn);
      throw e;
    }
    // Nothing locked on this connection: return the checkout and report the lock as held.
    closeQuietly(conn);
    return null;
  }

  /** A reaper's own domain action, run only once its candidate's lock is confirmed abandoned. */
  @FunctionalInterface
  public interface Reap {
    boolean run() throws SQLException;
  }

  /**
   * The shape every {@code *Reaper} (#711) applies to each of its own candidates: {@code key}'s
   * lock free means its owner is dead, so {@code onAbandoned} runs and its result is returned; held
   * means a live owner elsewhere, so this returns {@code false} and {@code onAbandoned} never runs.
   * The lock is held for {@code onAbandoned}'s whole run, so an owner that finishes in the race
   * window between the caller's own candidate SELECT and this call can never be downgraded
   * underneath it — the same guarantee {@link #tryAcquire} already gives a caller that inlines this
   * check itself.
   */
  public static boolean runIfAbandoned(AgroalDataSource adminDs, UUID key, Reap onAbandoned) throws SQLException {
    try (RunLock lock = tryAcquire(adminDs, key)) {
      return lock != null && onAbandoned.run();
    }
  }

  @Override
  public void close() {
    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      ps.setLong(1, key);
      ps.execute();
      closeQuietly(conn);
    } catch (SQLException e) {
      // The lock may still be held on this backend session, and returning it to the pool would
      // hand the lock to whoever borrows it next. End the session instead: Postgres drops a
      // session's advisory locks when it ends, and the pool discards the dead entry.
      LOG.warnf("Failed to release advisory lock %d: %s — closing its session instead", key, e.getMessage());
      destroy(conn);
    }
  }

  /**
   * Closes the PHYSICAL connection under the pool's wrapper, then returns the (dead) wrapper.
   * Package-private: {@link RunQueueClaim} applies the same policy to a session it cannot reset.
   */
  static void destroy(Connection conn) {
    try {
      conn.unwrap(Connection.class).close();
    } catch (SQLException e) {
      LOG.warnf("Could not close the session holding an advisory lock: %s", e.getMessage());
    } finally {
      closeQuietly(conn);
    }
  }

  /** 64-bit advisory key from a UUID. A collision between two keys is astronomically rare, and
   *  would only make two unrelated runs serialize now and then. That is harmless. */
  private static long key(UUID id) {
    return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
  }

  private static void closeQuietly(Connection conn) {
    try { conn.close(); } catch (SQLException ignored) { /* returning to pool */ }
  }
}
