package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.sql.TenantRlsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.lumnik.hub.workflow.WorkflowManifestParser.norm;

/**
 * Confronts a declared workflow with the values actually present in its table. A report,
 * never a police: undeclared values and unobserved states are surfaced, nothing blocks —
 * and no failure of the probe itself blocks either (see {@link Report#checkUnavailable}).
 * Runs on the READONLY (RLS-enforced) datasource under the caller's tenant — the check must
 * never see another tenant's values, and a report has no business holding a writable
 * connection. Same idiom as ViewDataReader. Caller must be @Transactional (SET LOCAL
 * precondition, see TenantRlsScope). Table/column identifiers are grammar-checked at parse
 * time (TABLE_RX/COLUMN_RX) — JDBC cannot bind an identifier, so the grammar is the guard.
 */
@ApplicationScoped
public class WorkflowDataTruth {

    private static final Logger LOG = Logger.getLogger(WorkflowDataTruth.class);
    private static final int LIMIT = 200;
    private static final String UNDEFINED_TABLE = "42P01";

    @Inject
    @io.quarkus.agroal.DataSource("readonly")
    AgroalDataSource readonlyDs;

    public record ValueCount(String value, long rows) {}

    /**
     * @param nullOrBlank rows whose status value is NULL or blank — their own bucket, never
     *        folded into silence: a column that is NULL on 90% of its rows must not report an
     *        empty {@code undeclared} that reads as "the data agrees with the manifest".
     * @param tableMissing the trigger table does not exist (yet, or anymore) — declaring before
     *        ingesting is a valid order of operations.
     * @param keyColumnMissing {@code spec.on.key} names a column the table does not have. Same
     *        "declared but not yet real" family as {@code tableMissing} — and the only signal a
     *        typo'd key ever produces: it passes the identifier grammar, applies with a clean 200,
     *        and then makes the dormancy sweep fail 42703 on every tick, in silence, forever.
     *        Reported, never refused: declaring before ingesting is legitimate.
     * @param checkUnavailable the confrontation could not be run at all (statement timeout,
     *        dropped column, revoked grant…). It means "we could not confront the data", NOT
     *        "the data agrees": the other fields of a degraded report are empty by ignorance.
     */
    public record Report(List<ValueCount> undeclared, List<String> unobserved, long nullOrBlank,
                         boolean tableMissing, boolean keyColumnMissing,
                         boolean checkUnavailable, String unavailableReason) {}

    public Report check(long tenantId, WorkflowGraph g) {
        Set<String> declared = new HashSet<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            declared.add(norm(s.code()));
            s.aliases().forEach(a -> declared.add(norm(a)));
        }

        Map<String, Long> seen = new LinkedHashMap<>();
        long nullOrBlank = 0L;
        boolean keyColumnMissing = false;
        String sql = "SELECT " + g.column() + "::text AS v, COUNT(*) FROM " + g.table()
            + " GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT " + LIMIT;
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v == null || v.isBlank()) nullOrBlank += rs.getLong(2);
                    else seen.put(v, rs.getLong(2));
                }
            }
            keyColumnMissing = g.key() != null && !hasColumn(c, g.table(), g.key());
        } catch (SQLException e) {
            if (UNDEFINED_TABLE.equals(e.getSQLState())) {
                return new Report(List.of(), List.of(), 0L, true, false, false, null);
            }
            // Everything else — a statement timeout on a 50M-row table, a dropped column, a
            // revoked grant — degrades the REPORT, never the declaration: the manifest is
            // already stored and "nothing ever blocks" (spec). The caller is told plainly
            // that the data was not confronted.
            LOG.warnf(e, "workflow data-truth check unavailable for tenant %d, workflow %s "
                + "(%s.%s, SQLSTATE %s)", tenantId, g.name(), g.table(), g.column(), e.getSQLState());
            return new Report(List.of(), List.of(), 0L, false, false, true,
                "SQLSTATE " + e.getSQLState() + ": " + e.getMessage());
        }

        List<ValueCount> undeclared = new ArrayList<>();
        Set<String> observedNorm = new HashSet<>();
        seen.forEach((v, n) -> {
            if (declared.contains(norm(v))) observedNorm.add(norm(v));
            else undeclared.add(new ValueCount(v, n));
        });

        List<String> unobserved = new ArrayList<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            boolean observed = observedNorm.contains(norm(s.code()))
                || s.aliases().stream().anyMatch(a -> observedNorm.contains(norm(a)));
            if (!observed) unobserved.add(s.code());
        }
        return new Report(List.copyOf(undeclared), List.copyOf(unobserved), nullOrBlank,
            false, keyColumnMissing, false, null);
    }

    /**
     * Does the table actually carry this column? Asked of the catalog, with BOUND parameters — a
     * {@code SELECT <key> FROM <table>} probe would answer by raising 42703, which aborts the
     * caller's transaction and would make the answer cost more than the question. Never throws for
     * "missing": absence of a row IS the answer.
     */
    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass(?) "
            + "AND attname = ? AND attnum > 0 AND NOT attisdropped";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
