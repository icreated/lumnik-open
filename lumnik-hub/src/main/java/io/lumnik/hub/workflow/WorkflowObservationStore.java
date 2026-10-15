package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The dormancy sweep's ledger (V33). since = when WE started observing the current state —
 * observation-relative by definition, never a claim about the source's own history.
 * Admin DS with explicit tenant binding (the cross-tenant scheduler pattern).
 */
@ApplicationScoped
public class WorkflowObservationStore {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public record Observation(String docKey, String state, Instant since,
                              Instant firstObservedAt, Instant alertedAt) {
        /** The ledger columns {@link #fromRow} reads, each behind {@code prefix} (a table alias with its dot, or ""). */
        static String columns(String prefix) {
            return prefix + "doc_key, " + prefix + "state, " + prefix + "since, "
                + prefix + "first_observed_at, " + prefix + "alerted_at";
        }

        /** Reads what {@link #columns} selected, in that order, starting at column {@code first}. */
        static Observation fromRow(ResultSet rs, int first) throws SQLException {
            Timestamp alerted = rs.getTimestamp(first + 4);
            return new Observation(rs.getString(first), rs.getString(first + 1),
                rs.getTimestamp(first + 2).toInstant(), rs.getTimestamp(first + 3).toInstant(),
                alerted == null ? null : alerted.toInstant());
        }
    }

    /** One tape row for the read surface — the flattened, cross-workflow view. */
    public record ObservationRow(String workflow, String docKey, String state,
                                 Instant since, Instant firstObservedAt, Instant alertedAt) {}

    /** One row per workflow: watched docs, currently-dormant count, median detection delay.
     *  A single PK-prefix range scan (PK = tenant_id, workflow_name, doc_key): the WHERE hits
     *  the PK's first column and GROUP BY its second — pre-sorted grouping, one round-trip,
     *  no per-workflow queries, whatever the volume. Median absent (null) when nothing is
     *  dormant — never a zero that would read as "detected instantly". */
    public record StatsRow(String workflow, long watched, long dormant, Long medianDetectionSeconds) {}

    public List<StatsRow> stats(long tenantId) {
        String sql = "SELECT workflow_name, COUNT(*) AS watched, "
            + "COUNT(*) FILTER (WHERE alerted_at IS NOT NULL) AS dormant, "
            + "percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (alerted_at - since))) "
            + "  FILTER (WHERE alerted_at IS NOT NULL) AS median_detection_seconds "
            + "FROM core.workflow_observation WHERE tenant_id = ? "
            + "GROUP BY workflow_name ORDER BY workflow_name";
        List<StatsRow> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double median = rs.getDouble("median_detection_seconds");
                    boolean noMedian = rs.wasNull();
                    out.add(new StatsRow(rs.getString("workflow_name"),
                        rs.getLong("watched"), rs.getLong("dormant"),
                        noMedian ? null : Math.round(median)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("workflow observation stats failed", e);
        }
        return out;
    }

    /** The tape, flattened across workflows: tenant-bound, stillest first, bounded in SQL.
     *  This is the read path. */
    public List<ObservationRow> listAll(long tenantId, int limit) {
        String sql = "SELECT workflow_name, doc_key, state, since, first_observed_at, alerted_at "
            + "FROM core.workflow_observation WHERE tenant_id = ? "
            + "ORDER BY since ASC, workflow_name, doc_key LIMIT ?";
        List<ObservationRow> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ObservationRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.listAll: " + e.getMessage(), e);
        }
        return out;
    }

    /** The whole ledger of one workflow as a map: for tests and small reads — the sweep streams it (#905). */
    public Map<String, Observation> loadAll(long tenantId, String workflow) {
        String sql = "SELECT " + Observation.columns("")
            + " FROM core.workflow_observation WHERE tenant_id = ? AND workflow_name = ?";
        Map<String, Observation> out = new HashMap<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, workflow);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), Observation.fromRow(rs, 1));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.loadAll: " + e.getMessage(), e);
        }
        return out;
    }

    public void insertNew(long tenantId, String workflow, String docKey, String state, Instant now) {
        String sql = "INSERT INTO core.workflow_observation "
            + "(tenant_id, workflow_name, doc_key, state, since, first_observed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId); ps.setString(2, workflow); ps.setString(3, docKey);
            ps.setString(4, state);
            ps.setTimestamp(5, Timestamp.from(now)); ps.setTimestamp(6, Timestamp.from(now));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.insertNew: " + e.getMessage(), e);
        }
    }

    /** State change: update the observation (since resets, alert flag clears) and append
     *  exactly one journal row — one connection, one local transaction, so the tape and the
     *  ledger cannot drift apart. */
    public void recordTransition(long tenantId, String workflow, String docKey,
                                 String fromState, String toState, Instant now) {
        try (Connection c = adminDs.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE core.workflow_observation SET state = ?, since = ?, "
                            + "alerted_at = NULL, updated_at = NOW() "
                            + "WHERE tenant_id = ? AND workflow_name = ? AND doc_key = ?")) {
                    up.setString(1, toState); up.setTimestamp(2, Timestamp.from(now));
                    up.setLong(3, tenantId); up.setString(4, workflow); up.setString(5, docKey);
                    up.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO core.workflow_transition "
                            + "(tenant_id, workflow_name, doc_key, from_state, to_state, observed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ins.setLong(1, tenantId); ins.setString(2, workflow); ins.setString(3, docKey);
                    ins.setString(4, fromState); ins.setString(5, toState);
                    ins.setTimestamp(6, Timestamp.from(now));
                    ins.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.recordTransition: " + e.getMessage(), e);
        }
    }

    public void markAlerted(long tenantId, String workflow, String docKey, Instant now) {
        String sql = "UPDATE core.workflow_observation SET alerted_at = ?, updated_at = NOW() "
            + "WHERE tenant_id = ? AND workflow_name = ? AND doc_key = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, tenantId); ps.setString(3, workflow); ps.setString(4, docKey);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.markAlerted: " + e.getMessage(), e);
        }
    }
}
