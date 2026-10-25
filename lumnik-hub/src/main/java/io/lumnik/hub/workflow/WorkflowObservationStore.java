package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
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
import java.util.Set;

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
            return new Observation(rs.getString(first), rs.getString(first + 1),
                Jdbc.instant(rs, first + 2), Jdbc.instant(rs, first + 3), Jdbc.instant(rs, first + 4));
        }

        Observation withState(String newState) {
            return new Observation(docKey, newState, since, firstObservedAt, alertedAt);
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

    /** Appended to a read of table alias {@code alias}: the rows of the workflows declared in the
     *  caller's scopes. Binds the granted scopes twice ({@code bindGrants}; null when unbound — every
     *  workflow), then the tenant again. */
    private static String inGrantedWorkflows(String alias) {
        return "AND (?::text[] IS NULL OR " + alias + ".workflow_name IN "
            + "(SELECT m.name FROM core.workflow_manifest m WHERE m.scope = ANY (?::text[]) "
            + "AND m.tenant_id = ?)) ";
    }

    public List<StatsRow> stats(long tenantId, Set<String> granted) {
        String sql = "SELECT workflow_name, COUNT(*) AS watched, "
            + "COUNT(*) FILTER (WHERE alerted_at IS NOT NULL) AS dormant, "
            + "percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (alerted_at - since))) "
            + "  FILTER (WHERE alerted_at IS NOT NULL) AS median_detection_seconds "
            + "FROM core.workflow_observation o WHERE o.tenant_id = ? " + inGrantedWorkflows("o")
            + "GROUP BY workflow_name ORDER BY workflow_name";
        List<StatsRow> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ScopeGuardedUpsert.bindGrants(ps, 2, granted);
            ps.setLong(4, tenantId);
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
     *  This is the read path; {@code granted} narrows it to the caller's scopes, null reads them all;
     *  {@code workflow} to one workflow's documents (#1361), null to every workflow. */
    public List<ObservationRow> listAll(long tenantId, Set<String> granted, String workflow, int limit) {
        String sql = "SELECT workflow_name, doc_key, state, since, first_observed_at, alerted_at "
            + "FROM core.workflow_observation o WHERE o.tenant_id = ? " + inGrantedWorkflows("o")
            + "AND (?::text IS NULL OR o.workflow_name = ?) "
            + "ORDER BY since ASC, workflow_name, doc_key LIMIT ?";
        List<ObservationRow> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ScopeGuardedUpsert.bindGrants(ps, 2, granted);
            ps.setLong(4, tenantId);
            ps.setString(5, workflow);
            ps.setString(6, workflow);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ObservationRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        Jdbc.instant(rs, 4), Jdbc.instant(rs, 5), Jdbc.instant(rs, 6)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.listAll: " + e.getMessage(), e);
        }
        return out;
    }

    /** One move of the journal (#1368). {@code enteredFromAt}: when the document entered {@code from} —
     *  its previous move, or its first observation for its first one (a lower bound, like since). */
    public record TransitionRow(String workflow, String docKey, String from, String to,
                                Instant enteredFromAt, Instant observedAt) {}

    /** The transition journal, newest first, tenant-bound, narrowed like {@link #listAll}; {@code doc}
     *  to one document, null to every one. A move whose ledger row is missing is left out rather than
     *  served without an entry time. The previous-move lookup is one index probe per kept row; the
     *  newest-first sort reads the workflow's (or tenant's) whole journal — fine for an on-demand read
     *  capped at 1000, an index on (tenant_id, observed_at) if journals grow large. */
    public List<TransitionRow> transitions(long tenantId, Set<String> granted, String workflow, String doc,
                                           int limit) {
        String sql = "SELECT t.workflow_name, t.doc_key, t.from_state, t.to_state, "
            + "COALESCE(p.observed_at, o.first_observed_at), t.observed_at "
            + "FROM core.workflow_transition t "
            + "LEFT JOIN LATERAL (SELECT q.observed_at FROM core.workflow_transition q "
            + "  WHERE q.tenant_id = t.tenant_id AND q.workflow_name = t.workflow_name AND q.doc_key = t.doc_key "
            + "  AND (q.observed_at, q.id) < (t.observed_at, t.id) ORDER BY q.observed_at DESC, q.id DESC LIMIT 1) p ON TRUE "
            + "JOIN core.workflow_observation o ON o.tenant_id = t.tenant_id "
            + "  AND o.workflow_name = t.workflow_name AND o.doc_key = t.doc_key "
            + "WHERE t.tenant_id = ? " + inGrantedWorkflows("t")
            + "AND (?::text IS NULL OR t.workflow_name = ?) AND (?::text IS NULL OR t.doc_key = ?) "
            + "ORDER BY t.observed_at DESC, t.id DESC LIMIT ?";
        List<TransitionRow> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ScopeGuardedUpsert.bindGrants(ps, 2, granted);
            ps.setLong(4, tenantId);
            ps.setString(5, workflow);
            ps.setString(6, workflow);
            ps.setString(7, doc);
            ps.setString(8, doc);
            ps.setInt(9, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransitionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), Jdbc.instant(rs, 5), Jdbc.instant(rs, 6)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.transitions failed", e);
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

    /** A declaration now names what the ledger held raw (#1367): the label changes; since, the alert
     *  flag and the journal do not — nothing moved. */
    public void relabel(long tenantId, String workflow, String docKey, String state) {
        String sql = "UPDATE core.workflow_observation SET state = ?, updated_at = NOW() "
            + "WHERE tenant_id = ? AND workflow_name = ? AND doc_key = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, tenantId); ps.setString(3, workflow); ps.setString(4, docKey);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("WorkflowObservationStore.relabel: " + e.getMessage(), e);
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
