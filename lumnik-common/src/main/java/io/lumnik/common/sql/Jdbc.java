package io.lumnik.common.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lambda-shaped JDBC for the plain acquire-bind-execute-map case: one statement on a fresh
 * connection, resources closed, failures wrapped with a caller-supplied label. The dominant
 * store shape on the admin datasource — dozens of hand-written twins of the same ceremony.
 * <p>
 * Deliberately NOT for multi-statement transactions: anything toggling autocommit or spanning
 * statements keeps its explicit try-with-resources, where the transaction boundary stays
 * visible at the call site.
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
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(label + ": " + e.getMessage(), e);
        }
    }

    /** Execute one SELECT; maps every row in order. */
    public static <T> List<T> query(DataSource ds, String label, String sql,
                                    Binder binder, RowMapper<T> mapper) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(label + ": " + e.getMessage(), e);
        }
    }

    /** Execute one SELECT; first row mapped, or empty — zero rows is an answer, not an error. */
    public static <T> Optional<T> queryOne(DataSource ds, String label, String sql,
                                           Binder binder, RowMapper<T> mapper) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(label + ": " + e.getMessage(), e);
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
     * rollback and rethrow (wrapped) on failure, autocommit restored either way. For a multi-
     * statement unit of work that must land atomically — {@link #update}/{@link #query} are for one
     * statement only, on their own independently-committed connection.
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
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            T result = null;
            Exception failure = null;
            try {
                result = body.apply(c);
                c.commit();
            } catch (Exception e) {
                failure = e;
                try {
                    c.rollback();
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            try {
                c.setAutoCommit(autoCommit);
            } catch (Exception restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
            if (failure != null) throw failure;
            return result;
        } catch (Exception e) {
            throw new RuntimeException(label + ": " + e.getMessage(), e);
        }
    }
}
