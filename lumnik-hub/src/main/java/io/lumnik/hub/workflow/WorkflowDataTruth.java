package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.process.MessageTemplate;
import io.lumnik.hub.sql.SqlFailureText;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static io.lumnik.hub.workflow.WorkflowManifestParser.norm;

/**
 * Confronts a declared workflow with the values actually present in its table. A report,
 * never a police: undeclared values and unobserved states are surfaced, nothing blocks —
 * and no failure of the probe itself blocks either (see {@link Report#checkUnavailable}).
 * Runs on the READONLY (RLS-enforced) datasource under the caller's tenant — the check must
 * never see another tenant's values, and a report has no business holding a writable
 * connection. Same idiom as ViewDataReader. Caller must be @Transactional (SET LOCAL
 * precondition, see TenantRlsScope). JDBC cannot bind an identifier, so every table and column
 * is interpolated QUOTED (SqlIdentifiers) — the escaping is the guard, which is what lets a
 * workflow watch the name the source actually chose ("Statut Commande", "MONTANT_€").
 */
@ApplicationScoped
public class WorkflowDataTruth {

    private static final Logger LOG = Logger.getLogger(WorkflowDataTruth.class);
    private static final int LIMIT = 200;
    private static final String UNDEFINED_TABLE = "42P01";

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    public record ValueCount(String value, long rows) {}

    /**
     * @param nullOrBlank rows whose status value is NULL or blank — their own bucket, never
     *        folded into silence: a column that is NULL on 90% of its rows must not report an
     *        empty {@code undeclared} that reads as "the data agrees with the manifest".
     * @param keyNullOrBlank rows whose {@code spec.on.key} is NULL or blank — the exact documents the
     *        dormancy sweep skips, for good. Counted independently of the status: a row with neither
     *        belongs to both diagnostics, so this and {@code nullOrBlank} are not buckets to add up.
     *        {@code null} means "not measured" (no key declared, key column or table missing, probe
     *        unavailable); {@code 0} only ever means a valid measure found no such row. Presence only:
     *        a key that is there may still be neither unique nor stable.
     * @param tableMissing the trigger table does not exist (yet, or anymore) — declaring before
     *        ingesting is a valid order of operations.
     * @param keyColumnMissing {@code spec.on.key} names a column the table does not have. Same
     *        "declared but not yet real" family as {@code tableMissing} — and the only signal a
     *        typo'd key ever produces: it applies with a clean 200, and then makes the dormancy
     *        sweep fail 42703 on every tick, in silence, forever. Reported, never refused —
     *        nothing at the door can tell a typo from a column not yet ingested, and declaring
     *        before ingesting is legitimate.
     * @param checkUnavailable the confrontation could not be run at all (statement timeout,
     *        dropped column, revoked grant…). It means "we could not confront the data", NOT
     *        "the data agrees": the other fields of a degraded report are empty by ignorance.
     * @param dueProblem what the due sweep would find wrong in {@code spec.due.column} (#1448): it is not
     *        there (yet), or it is not a date. {@code null} when it is readable, when no due date is
     *        declared, or when the probe did not run. Reported, never refused: declaring before ingesting
     *        is legitimate, and a column can be parsed into a date later.
     * @param invalidOrderValues rows whose {@code spec.on.order} value cannot place them (#1588): each makes its
     *        document unreadable to the sweeps. {@code null} means not measured (no order declared, or one of a type
     *        that cannot order).
     * @param keysWithSeveralRows keys a fused view holds more than once, measured for a due date over a view
     *        without {@code spec.on.order}: the due sweep then reads every one of their rows. {@code null} means not
     *        measured.
     * @param columnsUnverified the declared columns ({@code spec.on.order}, then those a dormancy or due decision
     *        cites or declares) that could not be checked because the relation does not exist yet; empty otherwise.
     */
    public record Report(List<ValueCount> undeclared, List<String> unobserved, long nullOrBlank,
                         boolean tableMissing, boolean keyColumnMissing, Long keyNullOrBlank,
                         boolean checkUnavailable, String unavailableReason, String dueProblem,
                         Long invalidOrderValues, Long keysWithSeveralRows, List<String> columnsUnverified) {}

    /**
     * Three movements: fold the declaration into the values it accepts, confront the data, compare.
     * The middle one is the only one that can fail, and it degrades into a Report rather than
     * throwing — "nothing ever blocks" (spec). Both degradation paths are pinned by
     * WorkflowDataTruthIT (absent table, unrunnable probe).
     */
    public Report check(long tenantId, WorkflowGraph g) {
        return check(tenantId, g, true);
    }

    /**
     * {@link #check} without the key and due-date probes: the status values alone, which is all the
     * ingestion check reads (#1589). A declared key costs a second scan of the table, once per run.
     */
    public Report checkValues(long tenantId, WorkflowGraph g) {
        return check(tenantId, g, false);
    }

    private Report check(long tenantId, WorkflowGraph g, boolean probes) {
        Set<String> declared = declaredValues(g);
        Observed observed;
        try {
            observed = confrontTheData(tenantId, g, probes);
        } catch (SQLException e) {
            return degraded(tenantId, g, e);
        }
        return compare(g, declared, observed);
    }

    /** What the data said: value counts, blanks, and whether the declared key column is even there. */
    private record Observed(Map<String, Long> seen, long nullOrBlank, boolean keyColumnMissing,
                            Long keyNullOrBlank, String dueProblem, Long invalidOrderValues,
                            Long keysWithSeveralRows) {}

    /** Every value the manifest accepts for this column — a state code or one of its aliases. */
    private static Set<String> declaredValues(WorkflowGraph g) {
        Set<String> declared = new HashSet<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            declared.add(norm(s.code()));
            s.aliases().forEach(a -> declared.add(norm(a)));
        }
        return declared;
    }

    private Observed confrontTheData(long tenantId, WorkflowGraph g, boolean probes) throws SQLException {
        // Read-only datasource, tenant-scoped. Quoting keeps a hostile name a NAME, so the worst
        // this statement can do is answer 42703 and degrade the report.
        String sql = "SELECT " + quoteIdent(g.column()) + "::text AS v, COUNT(*) FROM "
            + quoteTable(g.table()) + " GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT " + LIMIT;
        Map<String, Long> seen = new LinkedHashMap<>();
        long nullOrBlank = 0L;
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v == null || v.isBlank()) nullOrBlank += rs.getLong(2);
                    else seen.put(v, rs.getLong(2));
                }
            }
            if (!probes) return new Observed(seen, nullOrBlank, false, null, null, null, null);
            boolean keyMissing = g.key() != null && !hasColumn(c, g.table(), g.key());
            Long keyNullOrBlank = g.key() == null || keyMissing ? null : countUnusableKeys(c, g);
            Long invalidOrder = g.order() == null ? null : countInvalidOrder(c, g);
            Long severalRows = g.due() != null && g.order() == null && g.key() != null && !keyMissing
                && g.onFusedView() ? countKeysWithSeveralRows(c, g) : null;
            return new Observed(seen, nullOrBlank, keyMissing, keyNullOrBlank, dueProblem(c, g), invalidOrder,
                severalRows);
        }
    }

    /**
     * A probe that could not run degrades the REPORT, never the declaration: the manifest is already
     * stored and "nothing ever blocks" (spec). An absent table is an ordinary answer — the ingest has
     * not landed yet. Anything else — a statement timeout on a 50M-row table, a dropped column, a
     * revoked grant — is logged and the caller is told plainly that the data was not confronted.
     */
    static Report degraded(long tenantId, WorkflowGraph g, SQLException e) {
        if (UNDEFINED_TABLE.equals(e.getSQLState())) {
            return new Report(List.of(), List.of(), 0L, true, false, null, false, null, null, null, null,
                declaredColumns(g));
        }
        LOG.warnf(e, "workflow data-truth check unavailable for tenant %d, workflow %s "
            + "(%s.%s, SQLSTATE %s)", tenantId, g.name(), g.table(), g.column(), e.getSQLState());
        // rootMessage withholds a class that can carry a stored value (22 first) and then names
        // the SQLSTATE itself; a structural message keeps its words behind ours (#1315).
        String said = SqlFailureText.rootMessage(e);
        return new Report(List.of(), List.of(), 0L, false, false, null, true,
            said.startsWith("SQLSTATE ") ? said : "SQLSTATE " + e.getSQLState() + ": " + said, null, null, null,
            List.of());
    }

    /** What the data says that the manifest does not, and what the manifest says that the data never shows. */
    private static Report compare(WorkflowGraph g, Set<String> declared, Observed observed) {
        List<ValueCount> undeclared = new ArrayList<>();
        Set<String> observedNorm = new HashSet<>();
        observed.seen().forEach((v, n) -> {
            if (declared.contains(norm(v))) observedNorm.add(norm(v));
            else undeclared.add(new ValueCount(v, n));
        });

        List<String> unobserved = new ArrayList<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            boolean seen = observedNorm.contains(norm(s.code()))
                || s.aliases().stream().anyMatch(a -> observedNorm.contains(norm(a)));
            if (!seen) unobserved.add(s.code());
        }
        return new Report(List.copyOf(undeclared), List.copyOf(unobserved), observed.nullOrBlank(),
            false, observed.keyColumnMissing(), observed.keyNullOrBlank(), false, null, observed.dueProblem(),
            observed.invalidOrderValues(), observed.keysWithSeveralRows(), List.of());
    }

    private static String dueProblem(Connection c, WorkflowGraph g) throws SQLException {
        if (g.due() == null) return null;
        return DueColumn.problem(g.table(), g.due().column(), columnType(c, g.table(), g.due().column()));
    }

    /**
     * Is {@code spec.due.column} a timestamp with time zone? Asked by the apply door before it stores:
     * a zone declared over a column that carries its own instant is refused. As the readonly role, the
     * one the sweep reads the column as, so the door, the report and the sweep see the same catalog.
     */
    public boolean dueColumnIsAnInstant(WorkflowManifest m) {
        try (Connection c = readonlyDs.getConnection()) {
            return DueColumn.of(columnType(c, m.table(), m.due().column())) == DueColumn.TIMESTAMPTZ;
        } catch (SQLException e) {
            // the door's broad catch logs it and answers a stable error that quotes nothing of it
            throw new IllegalStateException("WorkflowDataTruth.dueColumnIsAnInstant", e);
        }
    }

    /** The columns the declaration names besides the watched ones (the order, then the cited and declared ones of the
     *  dormancy, then of the due date), which an absent relation leaves unverified. */
    static List<String> declaredColumns(WorkflowGraph g) {
        Set<String> out = new LinkedHashSet<>();
        if (g.order() != null) out.add(g.order());
        if (g.dormancy() != null) out.addAll(g.dormancy().columns());
        if (g.due() != null) out.addAll(g.due().columns());
        return List.copyOf(out);
    }

    /**
     * The first cited or declared column {@code m}'s relation does not have, said the way a Process says it (#1588);
     * null when every one is there, or when the relation is not there yet (declaring before ingesting is legitimate,
     * and the report lists them as unverified). Asked as the readonly role, like {@link #orderProblem}.
     */
    public String contextProblem(WorkflowManifest m) {
        try (Connection c = readonlyDs.getConnection()) {
            if (!relationExists(c, m.table())) return null;
            String missing = m.dormancy() == null ? null : firstMissing(c, m.table(), "spec.dormancy",
                m.dormancy().message(), DormancyMessage.PLACEHOLDERS, m.dormancy().fields());
            if (missing == null && m.due() != null)
                missing = firstMissing(c, m.table(), "spec.due", m.due().message(), DueMessage.PLACEHOLDERS,
                    m.due().fields());
            return missing;
        } catch (SQLException e) {
            // the door's broad catch logs it and answers a stable error that quotes nothing of it
            throw new IllegalStateException("WorkflowDataTruth.contextProblem", e);
        }
    }

    /** The refusal for the first cited, then declared, column {@code table} lacks — worded as a Process door words it,
     *  and a sentence's refusal lists the reserved names, so a misspelt {@code {since}} is not taken for a column. */
    private static String firstMissing(Connection c, String table, String path, String message, Set<String> reserved,
                                       List<String> fields) throws SQLException {
        for (String p : MessageTemplate.placeholders(message))
            if (!reserved.contains(p) && !hasColumn(c, table, p))
                return path + ".message references {" + p + "} — not a column of " + table + ", nor one of "
                    + reserved.stream().sorted().toList();
        for (String f : fields)
            if (!hasColumn(c, table, f)) return path + ".fields names " + f + " — not a column of " + table;
        return null;
    }

    /** Rows whose order value cannot place them — each makes its key unreadable to the sweeps (#1588). */
    private static Long countInvalidOrder(Connection c, WorkflowGraph g) throws SQLException {
        OrderColumn kind = OrderColumn.of(columnType(c, g.table(), g.order()));
        if (kind == null) return null;   // the door refuses it while the relation exists
        String key = quoteIdent(g.key());
        String sql = "SELECT COUNT(*) FROM " + quoteTable(g.table()) + " WHERE " + key + " IS NOT NULL AND btrim("
            + key + "::text) <> '' AND " + kind.expression(quoteIdent(g.order())) + " IS NULL";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Keys a view holds more than once: the due sweep reads every one of their rows without spec.on.order. */
    private static Long countKeysWithSeveralRows(Connection c, WorkflowGraph g) throws SQLException {
        String key = quoteIdent(g.key());
        String sql = "SELECT COUNT(*) FROM (SELECT " + key + " FROM " + quoteTable(g.table()) + " WHERE " + key
            + " IS NOT NULL AND btrim(" + key + "::text) <> '' GROUP BY " + key + " HAVING COUNT(*) > 1) s";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Why {@code spec.on.order} cannot order {@code m}'s relation; null when it can, or when the relation is not
     * there yet (declaring before ingesting is legitimate). Asked as the readonly role, like
     * {@link #dueColumnIsAnInstant}, so the door and the sweeps see the same catalog.
     */
    public String orderProblem(WorkflowManifest m) {
        if (m.order() == null) return null;
        try (Connection c = readonlyDs.getConnection()) {
            return OrderColumn.resolve(c, WorkflowGraph.from(m)) instanceof OrderColumn.Refused r ? r.problem() : null;
        } catch (SQLException e) {
            // the door's broad catch logs it and answers a stable error that quotes nothing of it
            throw new IllegalStateException("WorkflowDataTruth.orderProblem", e);
        }
    }

    /** Does the table or view exist? Asked of the catalog with both parts bound, as {@link #columnType} asks. */
    static boolean relationExists(Connection c, String table) {
        int dot = table.indexOf('.');
        return Jdbc.exists(c, "WorkflowDataTruth.relationExists",
            "SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = ? LIMIT 1", ps -> {
                ps.setString(1, dot < 0 ? "" : table.substring(0, dot));
                ps.setString(2, dot < 0 ? table : table.substring(dot + 1));
            });
    }

    /** The sweep's own predicate, negated: its {@code key IS NOT NULL AND btrim(key::text) <> ''}. */
    private static long countUnusableKeys(Connection c, WorkflowGraph g) throws SQLException {
        String key = quoteIdent(g.key());
        String sql = "SELECT COUNT(*) FROM " + quoteTable(g.table())
            + " WHERE " + key + " IS NULL OR btrim(" + key + "::text) = ''";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Does the table actually carry this column? Asked of the catalog with every part BOUND — a
     * {@code SELECT <key> FROM <table>} probe would answer by raising 42703, which aborts the
     * caller's transaction and would make the answer cost more than the question. Never throws for
     * "missing": absence of a row IS the answer.
     *
     * <p>Same shape as {@code ViewDetailReader.hasConflictsColumn}, and deliberately: three bound
     * parameters mean no identifier appears in this statement at all, so the name being free costs
     * nothing here. The alternative — {@code to_regclass(?)} — takes text but PARSES it as an
     * identifier, and a bare fqn would then answer NULL for any table whose name is not
     * lower-case-plain (measured on PG 16: {@code to_regclass('connector.t_Orders')} is NULL), i.e.
     * a false {@code keyColumnMissing} on the one flag a typo'd key raises.
     */
    static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        return columnType(c, table, column) != null;
    }

    /** The column's {@code information_schema} data type ({@code date}, {@code text}…), null when it is not
     *  there — asked the way {@link #hasColumn} asks, every part bound. The due sweep reads its date by it. */
    static String columnType(Connection c, String table, String column) throws SQLException {
        int dot = table.indexOf('.');
        String sql = "SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, dot < 0 ? "" : table.substring(0, dot));
            ps.setString(2, dot < 0 ? table : table.substring(dot + 1));
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
