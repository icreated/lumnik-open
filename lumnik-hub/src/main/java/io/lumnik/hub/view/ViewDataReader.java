package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;

/**
 * Reads a resolved kind:View's rows read-only, tenant-isolated — the same boundary as ask:
 * SET LOCAL app.current_tenant scopes RLS to the caller, SET LOCAL search_path = '' forces the
 * table to be schema-qualified (no bare-name search-path bypass). Must run inside the caller's
 * transaction (the SET LOCALs ride it). Column/table names come from the SchemaCard (already
 * validated by ViewResolver / ViewFilter) and are double-quoted; filter values are always bound
 * as parameters, never concatenated into the SQL.
 */
@ApplicationScoped
public class ViewDataReader {

    private static final int TIMEOUT_S = 5;
    private static final int MAX_ROWS = 1000;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    /** The rows plus the honest cut: {@code truncated} is true when the cap dropped rows. */
    public record ReadResult(List<Map<String,Object>> rows, boolean truncated) {}

    public ReadResult read(long tenantId, ResolvedView rv, List<ViewFilter> filters) {
        String sql = buildSql(rv, filters);
        List<Map<String,Object>> out = new ArrayList<>();
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(TIMEOUT_S);
                // Fetch one row beyond the cap: the only way to tell a full 1000-row result
                // from a truncated one — a view never lies, so the cut must be spoken.
                ps.setMaxRows(MAX_ROWS + 1);
                for (int i = 0; i < filters.size(); i++) {
                    ViewFilter f = filters.get(i);
                    ps.setString(i + 1, "~".equals(f.op()) ? containsPattern(f.value()) : f.value());
                }
                // LIMIT's "?" is the last placeholder buildSql emits, after every filter's — so
                // it binds at filters.size() + 1, one past the last filter index above.
                ps.setInt(filters.size() + 1, MAX_ROWS + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        out.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            throw mapSqlFailure(e);
        }
        boolean truncated = out.size() > MAX_ROWS;
        return new ReadResult(truncated ? out.subList(0, MAX_ROWS) : out, truncated);
    }

    /**
     * A view must never fail without saying why in métier language: date-shaped garbage and the
     * statement-timeout cancel (57014, from {@code setQueryTimeout}) ride the honest-refusal
     * channel (400); anything else stays a genuine failure.
     */
    static RuntimeException mapSqlFailure(SQLException e) {
        String state = e.getSQLState();
        if ("22007".equals(state) || "22008".equals(state))
            return new IllegalArgumentException(
                "a row holds a value that is date-shaped but not a real date — "
                + "an ordered date filter cannot be applied over this column");
        if ("57014".equals(state))
            return new IllegalArgumentException(
                "the view took longer than " + TIMEOUT_S + " s to render — "
                + "narrow it with filters or declare fewer columns");
        return new RuntimeException("ViewDataReader.read: " + e.getMessage(), e);
    }

    static String buildSql(ResolvedView rv, List<ViewFilter> filters) {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<ResolvedView.Column> cols = rv.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(cols.get(i).name()));
        }
        sb.append(" FROM ").append(quoteTable(rv.table()));
        for (int i = 0; i < filters.size(); i++) {
            ViewFilter f = filters.get(i);
            sb.append(i == 0 ? " WHERE " : " AND ").append(sqlFor(f, pgTypeOf(cols, f.column())));
        }
        if (!cols.isEmpty()) sb.append(" ORDER BY ").append(quoteIdent(cols.get(0).name()));
        // A real LIMIT, not just the JDBC-side setMaxRows: ORDER BY is a blocking operator, so
        // without this Postgres still plans and sorts the whole table before the driver ever
        // gets to discard rows. Bound, never concatenated; +1 preserves the "fetch one row
        // beyond the cap" trick that lets truncated (:below) speak the truth.
        sb.append(" LIMIT ?");
        return sb.toString();
    }

    /** The column's declared Postgres type from the resolved view, or null when the view does not
     *  know it — in which case {@link #sqlFor} keeps its cast (fail safe). */
    private static String pgTypeOf(List<ResolvedView.Column> cols, String name) {
        for (ResolvedView.Column c : cols) {
            if (c.name().equals(name)) return c.pgType();
        }
        return null;
    }

    /** Whether a column is already textual, so comparing it needs no cast at all. The vocabulary is
     *  information_schema's ({@code data_type}), which spells the varchar family out in words. */
    private static boolean isTextType(String pgType) {
        if (pgType == null) return false;
        return switch (pgType.toLowerCase(Locale.ROOT)) {
            case "text", "varchar", "character varying", "char", "character", "bpchar" -> true;
            default -> false;
        };
    }

    /** The SQL fragment for one filter. Ordering comparisons ride a CASE guard: PG does not
     *  guarantee WHERE predicate order, so a bare AND shape-check could cast a dirty row first
     *  and 500 — CASE guarantees evaluation order; a dirty row falls to ELSE false (the
     *  incomparable never matches). The operator and shape come from closed internal sets;
     *  the value stays bound.
     *
     *  <p>A cast on the LEFT-hand side makes any btree index on that column unusable, so equality
     *  drops it when the column is ALREADY text — same semantics, index preserved. The narrowing is
     *  deliberately opt-in and stops there: casting the parameter instead ({@code col = ?::numeric})
     *  would make the other types index-friendly too, but a filter value that does not parse would
     *  then raise 22P02 where today it simply matches nothing, and the view has no honest refusal
     *  for that. ILIKE keeps its cast because it genuinely needs text semantics — and a
     *  {@code %value%} pattern cannot use a plain btree anyway, so there is nothing to win. */
    private static String sqlFor(ViewFilter f, String pgType) {
        String col = quoteIdent(f.column());
        if ("~".equals(f.op())) return col + "::text ILIKE ? ESCAPE '\\'";
        if ("=".equals(f.op())) return isTextType(pgType) ? col + " = ?" : col + "::text = ?";
        if (!f.ordering() || f.operandType() == null)
            throw new IllegalStateException("not a valid ordering filter: " + f.op() + " " + f.value());
        boolean numeric = f.operandType() == ViewFilter.OperandType.NUMERIC;
        String shape = numeric ? ViewFilter.NUMERIC_SHAPE : ViewFilter.TEMPORAL_SHAPE;
        String cast = numeric ? "numeric" : "timestamptz";
        return "CASE WHEN " + col + "::text ~ '" + shape + "' "
             + "THEN (" + col + "::text)::" + cast + " " + f.op() + " ?::" + cast + " "
             + "ELSE false END";
    }

    /** %value% with user \, %, _ escaped so they match literally. */
    private static String containsPattern(String v) {
        return "%" + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
