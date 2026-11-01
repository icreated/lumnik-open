package io.lumnik.common.sql;

import io.lumnik.common.error.Throwables;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lambda-shaped JDBC for the plain bind-execute-map case: one statement, on a fresh connection from a
 * {@link DataSource} or on one the caller already holds (a JTA connection, a {@link #transaction} body's),
 * what the helper opened closed, database failures wrapped with a caller-supplied label (see
 * {@link #failure}). The dominant store shape — dozens of hand-written twins of the same ceremony.
 * <p>
 * Several statements that must land together go through {@link #transaction}, whose body runs these
 * same helpers on its connection: the boundary stays visible at the call site.
 */
public final class Jdbc {

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement ps) throws Exception;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws Exception;
    }

    private Jdbc() {}

    /** Execute one INSERT/UPDATE/DELETE; returns the affected-row count. */
    public static int update(DataSource ds, String label, String sql, Binder binder) {
        try (Connection c = ds.getConnection()) {
            return update(c, label, sql, binder);
        } catch (SQLException e) {
            throw failure(label, e);
        }
    }

    /** {@link #update(DataSource, String, String, Binder)} on a connection the caller holds — a JTA one,
     *  a transaction body's — which stays open (#1300). */
    public static int update(Connection c, String label, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw failure(label, e);
        }
    }

    /** Execute one SELECT; maps every row in order. */
    public static <T> List<T> query(DataSource ds, String label, String sql,
                                    Binder binder, RowMapper<T> mapper) {
        try (Connection c = ds.getConnection()) {
            return query(c, label, sql, binder, mapper);
        } catch (SQLException e) {
            throw failure(label, e);
        }
    }

    /** {@link #query(DataSource, String, String, Binder, RowMapper)} on a connection the caller holds. */
    public static <T> List<T> query(Connection c, String label, String sql,
                                    Binder binder, RowMapper<T> mapper) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (Exception e) {
            throw failure(label, e);
        }
    }

    /** Execute one SELECT; first row mapped, or empty — zero rows, or a mapper answering null, is an
     *  answer, not an error. */
    public static <T> Optional<T> queryOne(DataSource ds, String label, String sql,
                                           Binder binder, RowMapper<T> mapper) {
        try (Connection c = ds.getConnection()) {
            return queryOne(c, label, sql, binder, mapper);
        } catch (SQLException e) {
            throw failure(label, e);
        }
    }

    /**
     * A transaction-scoped advisory lock on the hash of {@code key}, held until the connection's transaction ends:
     * writers of one logical row that SQL cannot lock (a DELETE then an INSERT over rows that may not exist yet) queue
     * behind it (#1658). The key shares one database-wide space with every other user of the idiom, so it carries its
     * owner as a prefix ({@code "rag.chunk:" + ...}, as {@code EntityDefinitionLock} does). The wait has no bound of its
     * own: set {@code lock_timeout} where a holder can stall.
     */
    public static void advisoryXactLock(Connection c, String label, String key) {
        queryOne(c, label, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ps -> ps.setString(1, key), rs -> true);
    }

    /** {@link #queryOne(DataSource, String, String, Binder, RowMapper)} on a connection the caller holds. */
    public static <T> Optional<T> queryOne(Connection c, String label, String sql,
                                           Binder binder, RowMapper<T> mapper) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw failure(label, e);
        }
    }

    /** Execute one SELECT; whether it found a row — the {@code SELECT 1 …} shape. */
    public static boolean exists(DataSource ds, String label, String sql, Binder binder) {
        return queryOne(ds, label, sql, binder, rs -> true).isPresent();
    }

    /** {@link #exists(DataSource, String, String, Binder)} on a connection the caller holds. */
    public static boolean exists(Connection c, String label, String sql, Binder binder) {
        return queryOne(c, label, sql, binder, rs -> true).isPresent();
    }

    /** A timestamptz column as an Instant; SQL NULL → null. Keeps the microseconds. */
    public static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    /** {@link #instant(ResultSet, int)} by column label. */
    public static Instant instant(ResultSet rs, String col) throws SQLException {
        return instant(rs, rs.findColumn(col));
    }

    /**
     * The SQLSTATE of the first {@link SQLException} in the chain that has one, or null.
     *
     * <p>Three hand-written copies of this walk existed — two spelled {@code isUniqueViolation},
     * one {@code sqlStateOf} — each re-deriving the loop {@link Throwables#causeChain} was written to
     * retire, and each unbounded. The bound is the whole point: A→B→A is constructible, and the readers
     * are a request thread, a scheduled sweep and a login path. One walk cannot diverge. It left the
     * hub's {@code SqlFailureText} for here when {@link #failure} became a reader too (#1299).
     */
    public static String sqlStateOf(Throwable failure) {
        for (Throwable t : Throwables.causeChain(failure)) {
            if (t instanceof SQLException se && se.getSQLState() != null) return se.getSQLState();
        }
        return null;
    }

    /**
     * What a failure inside one of these helpers becomes (#1299). A database failure — a {@link SQLException}
     * anywhere in the chain — is wrapped with the label and its SQLSTATE, never the driver's text:
     * pgjdbc's {@code Detail:} quotes the row's values, and a wrapper's message travels (a 500 body, a
     * served {@code last_error}). The cause stays attached, so the log keeps every character. Any other
     * unchecked exception is the caller's own — a typed 400/403/409 thrown inside a lambda — and passes
     * through untouched. Any other checked exception is wrapped with its class only.
     *
     * <p>Stricter than the hub's {@code SqlFailureText.rootMessage}, which lets a structural class's text
     * through: this message has no audience to judge, so it says nothing the SQLSTATE does not.
     */
    private static RuntimeException failure(String label, Exception e) {
        return switch (e) {
            // A statement inside a transaction body already said which one failed (#1300).
            case Failure statement -> statement;
            case RuntimeException own when !isDatabase(e) -> own;
            default -> new Failure(failureText(label, e), e);
        };
    }

    /** The message {@link #failure} builds, for a block that cannot use these helpers (a batch, a cursor)
     *  and keeps its own exception type: {@code throw new X(Jdbc.failureText("Cls.op", e), e)} (#1648). */
    public static String failureText(String label, Throwable e) {
        String state = sqlStateOf(e);
        // A caller re-labelling one of these helpers' failures names what it wraps, not the wrapper (Copilot on #1666).
        Throwable named = e instanceof Failure && e.getCause() != null ? e.getCause() : e;
        String why = !isDatabase(e) ? named.getClass().getSimpleName()
            : state == null ? "no SQLSTATE" : "SQLSTATE " + state;
        return label + " failed (" + why + ")";
    }

    private static boolean isDatabase(Throwable e) {
        return Throwables.causeChain(e).stream().anyMatch(SQLException.class::isInstance);
    }

    /** What {@link #failure} builds — a plain RuntimeException to every caller, recognisable here only. */
    private static final class Failure extends RuntimeException {
        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    public interface TxBody {
        void run(Connection c) throws Exception;
    }

    @FunctionalInterface
    public interface TxFunction<T> {
        T apply(Connection c) throws Exception;
    }

    /**
     * Runs {@code body} in one explicit transaction on a fresh connection: commit on success,
     * rollback and rethrow (as {@link #failure} says) on failure — {@link #transaction(Connection, String, TxFunction)}
     * on a connection it opens and closes, which the pool must hand out in autocommit.
     * For a multi-statement unit of work that must land atomically: the body runs each statement
     * through the {@code Connection} forms of {@link #update}/{@link #query}/{@link #queryOne}.
     */
    public static void transaction(DataSource ds, String label, TxBody body) {
        transaction(ds, label, (Connection c) -> {
            body.run(c);
            return null;
        });
    }

    /** {@link #transaction(DataSource, String, TxBody)}'s own value-returning twin — same
     *  commit/rollback/autocommit-restore ceremony, for a caller whose body derives a result (e.g.
     *  a typed outcome read under the same row lock a following write depends on) that must travel
     *  out with the commit. */
    public static <T> T transaction(DataSource ds, String label, TxFunction<T> body) {
        try (Connection c = ds.getConnection()) {
            return transaction(c, label, body);
        } catch (Exception e) {
            throw failure(label, e);
        }
    }

    /**
     * {@link #transaction(DataSource, String, TxFunction)} on a connection the caller holds and keeps — a check made
     * before in autocommit, a {@code WITH HOLD} cursor read after the commit (#1649). Refuses a connection already out
     * of autocommit: it is inside a transaction someone else owns (a JTA one, the caller's), never this helper's to
     * commit.
     *
     * <p>Nothing the body did is committed unless it returned: an {@link Error} rolls back too (#1607's shape), and
     * leaves as itself. A transaction left open would not be lost but committed: given back to the pool, Agroal resets
     * the connection with {@code setAutoCommit(true)}, on which pgjdbc COMMITs. So when the rollback or the restore
     * after it fails, the session is ended instead ({@link #endSession}), as {@code RunQueueClaim} does (#952): the
     * server rolls back what it held, and the caller's connection is dead. That failure rides on the body's as
     * suppressed; an Error leaves in its place.
     */
    public static <T> T transaction(Connection c, String label, TxFunction<T> body) {
        try {
            if (!c.getAutoCommit())
                throw new IllegalStateException(label + ": Jdbc.transaction owns its transaction — "
                    + "this connection is already in one");
            c.setAutoCommit(false);
            T result;
            try {
                result = body.apply(c);
                c.commit();
            } catch (Throwable t) {
                rollBack(c, t);
                throw t;
            }
            c.setAutoCommit(true);
            return result;
        } catch (Exception e) {
            // An Error leaves as itself. The refusal comes back from failure() unchanged.
            throw failure(label, e);
        }
    }

    /** Rolls back the transaction {@code failure} interrupted and restores autocommit — or ends the session. */
    private static void rollBack(Connection c, Throwable failure) {
        boolean reset = false;
        try {
            c.rollback();
            c.setAutoCommit(true);
            reset = true;
        } catch (Exception cleanupFailure) {
            // A stub or a driver may throw the body's own exception again: addSuppressed would refuse it (Copilot on #1657).
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        } finally {
            if (!reset) {
                try {
                    endSession(c);
                } catch (Exception endFailure) {
                    if (endFailure != failure) failure.addSuppressed(endFailure);
                }
            }
        }
    }

    /**
     * Ends the database session under {@code c}: closes the physical connection beneath a pool's wrapper, and the
     * server rolls back whatever the session still held — an open transaction, a lock. Closing the wrapper would only
     * give the session back to the pool, and Agroal's {@code abort} does not reach it. The wrapper stays its owner's
     * to close: back in the pool, its reset fails on the dead session, which PostgreSQL's exception sorter calls
     * fatal (SQLSTATE class 08), and the pool discards it.
     */
    public static void endSession(Connection c) throws SQLException {
        c.unwrap(Connection.class).close();
    }
}
