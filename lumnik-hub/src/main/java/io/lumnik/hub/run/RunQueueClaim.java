package io.lumnik.hub.run;

import io.agroal.api.AgroalDataSource;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * One transaction on the {@code admin} datasource that owns one {@code Queued} row (#952, D2 steps 2–3):
 * the row is selected {@code FOR UPDATE SKIP LOCKED}, so two dispatchers can never pick the same one and
 * neither waits for the other. The transaction stays open until {@link #commit()} or {@link #close()},
 * which rolls back whatever was not committed — so a start attempt that leaves a row {@code Queued} (a
 * busy lock) is simply a claim that is closed.
 *
 * <p>{@code SKIP LOCKED} only exists inside a transaction, which is why the row is chosen BEFORE its
 * {@link RunLock}: choosing the lock first would let two dispatchers pick one row and find each other's
 * lock on it.
 */
public final class RunQueueClaim<T> implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RunQueueClaim.class);

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection conn;
    private final T row;
    private boolean committed;

    private RunQueueClaim(Connection conn, T row) {
        this.conn = conn;
        this.row = row;
    }

    /**
     * Opens the transaction and claims the next row, or answers empty (the connection is released at once).
     * {@code sql} takes ONE parameter, the {@code uuid[]} of ids already tried this poll, returns at most one
     * row and ends {@code FOR UPDATE SKIP LOCKED}.
     */
    public static <T> Optional<RunQueueClaim<T>> next(AgroalDataSource ds, String sql, Collection<UUID> tried,
                                                      RowMapper<T> mapper) throws SQLException {
        Connection conn = ds.getConnection();
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setArray(1, conn.createArrayOf("uuid", tried.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(new RunQueueClaim<>(conn, mapper.map(rs)));
                }
            }
        } catch (SQLException | RuntimeException e) {
            release(conn, false);
            throw e;
        }
        release(conn, false);
        return Optional.empty();
    }

    public T row() {
        return row;
    }

    /** The claim's own connection: a start or an ending written on it commits with {@link #commit()}. */
    public Connection connection() {
        return conn;
    }

    public void commit() throws SQLException {
        conn.commit();
        committed = true;
    }

    @Override
    public void close() {
        release(conn, committed);
    }

    /**
     * Package-private: {@code RunQueueClaimReleaseTest} drives it with a simulated connection. Rolled back
     * and reset, the connection goes back to the pool. If either fails, the session may still hold the row's
     * {@code FOR UPDATE} lock (or an open transaction), and pooling it would hand that to the next borrower
     * and hide the row from every dispatcher: it is destroyed instead, as {@link RunLock#close()} does for an
     * advisory lock it cannot release.
     */
    static void release(Connection conn, boolean committed) {
        boolean reset = false;
        try {
            if (!committed) conn.rollback();
            conn.setAutoCommit(true);
            reset = true;
        } catch (SQLException | RuntimeException e) {
            LOG.warnf("a run-queue claim could not be rolled back and reset (%s) — closing its session instead of pooling it",
                e.getMessage());
        }
        if (reset) {
            try { conn.close(); } catch (SQLException ignored) { /* returning to the pool */ }
        } else {
            RunLock.destroy(conn);
        }
    }
}
