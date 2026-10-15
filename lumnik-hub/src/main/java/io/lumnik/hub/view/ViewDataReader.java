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
                bindFilters(ps, filters, 0);
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


    /** One served page: the rows, whether more exist beyond them, and where the next page starts. */
    public record PageResult(List<Map<String,Object>> rows, boolean hasMore, ViewCursor.Boundary next) {}

    /** What to read: at most {@code size} rows, starting after {@code after} (null = the first page). */
    public record Page(int size, ViewCursor.Boundary after) {}

    /** A page's SQL, the boundary values it binds before the filters (in order), and its OFFSET if it ends in one. */
    record PageSql(String sql, List<Object> boundaryParams, boolean offset, long offsetRows) {}

    /**
     * One page of a View in the contract's order ({@link ViewPageContract#orderKey}): fetches
     * {@code size + 1} rows so {@code hasMore} is true without a COUNT. A boundary row that has
     * since been deleted cannot be resumed from, and is the same refusal as any other stale cursor.
     */
    public PageResult readPage(long tenantId, ResolvedView rv, List<ViewFilter> filters,
                               boolean relationHasId, Page page) {
        PageSql q = buildPageSql(rv, filters, relationHasId, page.after());
        int n = rv.columns().size();
        List<Map<String,Object>> rows = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenantId);
            if (relationHasId && page.after() != null && !boundaryRowExists(c, rv, q.boundaryParams().get(0)))
                throw new IllegalArgumentException(ViewCursor.REFUSAL);
            try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                ps.setQueryTimeout(TIMEOUT_S);
                int at = 0;
                for (Object p : q.boundaryParams()) ps.setObject(++at, p);
                at = bindFilters(ps, filters, at);
                ps.setInt(++at, page.size() + 1);
                if (q.offset()) ps.setLong(++at, q.offsetRows());
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                        if (relationHasId) ids.add(rs.getLong(n + 1));
                    }
                }
            }
        } catch (SQLException e) {
            throw mapSqlFailure(e);
        }
        boolean hasMore = rows.size() > page.size();
        List<Map<String,Object>> served = hasMore ? new ArrayList<>(rows.subList(0, page.size())) : rows;
        if (!hasMore || served.isEmpty()) return new PageResult(served, hasMore, null);
        return new PageResult(served, true, nextBoundary(relationHasId, served, ids, page.after()));
    }

    /** Where the page after {@code served} starts. */
    private static ViewCursor.Boundary nextBoundary(boolean relationHasId, List<Map<String,Object>> served,
                                                    List<Long> ids, ViewCursor.Boundary previous) {
        if (relationHasId) return new ViewCursor.Boundary(List.of(Long.toString(ids.get(served.size() - 1))));
        long start = previous == null ? 0 : positionOf(previous);
        return new ViewCursor.Boundary(List.of(Long.toString(start + served.size())));
    }

    private static boolean boundaryRowExists(Connection c, ResolvedView rv, Object id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + quoteTable(rv.table()) + " WHERE " + quoteIdent("id") + " = ?")) {
            ps.setQueryTimeout(TIMEOUT_S);
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Binds every filter value after index {@code last}; returns the last index used. */
    private static int bindFilters(PreparedStatement ps, List<ViewFilter> filters, int last) throws SQLException {
        for (ViewFilter f : filters) {
            ps.setString(++last, "~".equals(f.op()) ? containsPattern(f.value()) : f.value());
        }
        return last;
    }

    /**
     * The SQL of one page. The order is {@link ViewPageContract#orderKey}, nulls last; the boundary
     * and the page size are bound, never concatenated. With an {@code id}, the boundary row's own
     * first-column value is read in SQL (any column type, no value round-trip). Without one — an
     * entity view, a fused SELECT recomputed whole on every read, so a keyset would save nothing —
     * the order is total over every declared column and the page starts at a bound OFFSET: the
     * cursor carries a position, never row text.
     */
    static PageSql buildPageSql(ResolvedView rv, List<ViewFilter> filters, boolean relationHasId,
                                ViewCursor.Boundary after) {
        List<ResolvedView.Column> cols = rv.columns();
        List<String> key = ViewPageContract.orderKey(rv, relationHasId);
        List<Object> params = new ArrayList<>();
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(cols.get(i).name()));
        }
        if (relationHasId) sb.append(", ").append(quoteIdent("id"));
        sb.append(" FROM ").append(quoteTable(rv.table()));
        // Placeholders are numbered by their position in the text: the boundary (its sub-select is in
        // FROM, its predicate leads WHERE) comes before every filter, so it is bound first.
        List<String> predicates = new ArrayList<>();
        boolean offset = false;
        long offsetRows = 0;
        if (after != null) {
            if (relationHasId) {
                predicates.add(keysetAfterId(rv, key, idOf(after), sb, params));
            } else {
                offsetRows = positionOf(after);
                offset = true;
            }
        }
        for (ViewFilter f : filters) predicates.add(sqlFor(f, pgTypeOf(cols, f.column())));
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) where.append(i == 0 ? " WHERE " : " AND ").append(predicates.get(i));
        sb.append(where).append(" ORDER BY ");
        for (int i = 0; i < key.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIdent(key.get(i)));
            if (!(relationHasId && i == key.size() - 1)) sb.append(" NULLS LAST");
        }
        sb.append(" LIMIT ?");
        if (offset) sb.append(" OFFSET ?");
        return new PageSql(sb.toString(), params, offset, offsetRows);
    }

    private static long idOf(ViewCursor.Boundary after) {
        return singleNumber(after);
    }

    /** The position an id-less relation's next page starts at. */
    private static long positionOf(ViewCursor.Boundary after) {
        return singleNumber(after);
    }

    private static long singleNumber(ViewCursor.Boundary after) {
        if (after.values().size() != 1 || after.values().get(0) == null) throw new IllegalArgumentException(ViewCursor.REFUSAL);
        try {
            long n = Long.parseLong(after.values().get(0));
            if (n < 0) throw new IllegalArgumentException(ViewCursor.REFUSAL);
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ViewCursor.REFUSAL);
        }
    }

    /** Rows after the boundary row {@code id} in (first column nulls last, id) order, or after id alone. */
    private static String keysetAfterId(ResolvedView rv, List<String> key, long id, StringBuilder from,
                                        List<Object> params) {
        String idCol = quoteIdent("id");
        if (key.size() == 1) {
            params.add(id);
            return idCol + " > ?";
        }
        String first = quoteIdent(key.get(0));
        // The boundary row's first-column value, read in SQL so its type never leaves the database.
        from.append(", (SELECT ").append(first).append(" AS bc FROM ").append(quoteTable(rv.table()))
            .append(" WHERE ").append(idCol).append(" = ?) b");
        params.add(id);
        params.add(id);
        params.add(id);
        return "CASE WHEN b.bc IS NULL THEN (" + first + " IS NULL AND " + idCol + " > ?) "
             + "ELSE (" + first + " > b.bc OR " + first + " IS NULL OR (" + first + " = b.bc AND " + idCol + " > ?)) END";
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

    /** The SQL fragment for one filter. The operator and shape come from closed internal sets; the value
     *  stays bound. Ordering is {@link ColumnKind}'s call: the kind of the column's TYPE (a declared
     *  domain narrows what {@code =} accepts, not how the type orders) both gates the filter
     *  ({@link ColumnKind#orders(String)}) and builds the SQL, so the two cannot drift.
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
        if ("=".equals(f.op())) return ColumnKind.isTextual(pgType) ? col + " = ?" : col + "::text = ?";
        if (!f.ordering() || f.operandType() == null)
            throw new IllegalStateException("not a valid ordering filter: " + f.op() + " " + f.value());
        return ColumnKind.ofType(pgType).orderingSql(col, f.op(), f.operandType());
    }

    /** %value% with user \, %, _ escaped so they match literally. */
    private static String containsPattern(String v) {
        return "%" + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
