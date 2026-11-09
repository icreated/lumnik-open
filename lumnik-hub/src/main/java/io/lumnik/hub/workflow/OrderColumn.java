package io.lumnik.hub.workflow;

import io.lumnik.common.sql.Jdbc;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;

/**
 * What a {@code spec.on.order} column can be (#1588), by its {@code information_schema} data type, and the SQL that
 * orders by it. A date, a timestamp or a number orders as itself, as long as it is finite: PostgreSQL sorts NaN and
 * infinity above every real value, and a garbage version would stay current forever. Text orders only as an ISO 8601
 * instant with an explicit offset, compared as an instant and never as text: {@code 12:00+03:00} is before
 * {@code 10:00Z}. Its shape is checked first, so the connection's time zone never decides what a zoneless value means,
 * then PostgreSQL's own {@code pg_input_is_valid}, and only then the cast. Anything else orders as NULL: an unknown
 * position, which makes its document unreadable.
 */
enum OrderColumn {
    DATE, TIMESTAMP, TIMESTAMPTZ, INTEGER, DECIMAL, FLOAT, TEXT;

    private static final Map<String, OrderColumn> BY_TYPE = Map.ofEntries(
        Map.entry("date", DATE),
        Map.entry("timestamp without time zone", TIMESTAMP),
        Map.entry("timestamp with time zone", TIMESTAMPTZ),
        Map.entry("integer", INTEGER), Map.entry("bigint", INTEGER), Map.entry("smallint", INTEGER),
        Map.entry("numeric", DECIMAL), Map.entry("real", FLOAT), Map.entry("double precision", FLOAT),
        Map.entry("text", TEXT), Map.entry("character varying", TEXT), Map.entry("character", TEXT));

    /** RFC 3339's shape: a date, T or a space, a time, then Z (either case) or ±HH:MM. Seconds and fractions optional. */
    static final String INSTANT_RX =
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]+)?)?([Zz]|[+-][0-9]{2}:[0-9]{2})$";

    /** What the catalog says of a workflow's order column this tick. */
    sealed interface Resolved permits Orders, Refused, RelationMissing {}

    /** The column can order the versions, as this kind. */
    record Orders(OrderColumn kind) implements Resolved {}

    /** The column cannot order the versions, and why. */
    record Refused(String problem) implements Resolved {}

    /** The watched relation itself is not there: neither the column's fault nor its business. */
    record RelationMissing() implements Resolved {}

    /** Asks the catalog what {@code g}'s order column is, as the role that reads the documents. */
    static Resolved resolve(Connection c, WorkflowGraph g) throws SQLException {
        if (!WorkflowDataTruth.relationExists(c, g.table())) return new RelationMissing();
        String type = WorkflowDataTruth.columnType(c, g.table(), g.order());
        String problem = problem(g.table(), g.order(), type);
        return problem == null ? new Orders(of(type)) : new Refused(problem);
    }

    /**
     * {@link #resolve} for a sweep, on every tick: a refused column stands the workflow down and is said once, its
     * return is said once. The caller decides what a missing relation means.
     */
    static Resolved forSweep(DataSource readonly, WorkflowConditions conditions, long tenant, WorkflowGraph g) {
        Resolved order;
        try (Connection c = readonly.getConnection()) {
            order = resolve(c, g);
        } catch (SQLException e) {
            throw new IllegalStateException(Jdbc.failureText("OrderColumn.forSweep", e), e);
        }
        if (order instanceof Refused r)
            conditions.standDown(tenant, g.name(), WorkflowConditions.ORDER, r.problem(),
                "Nothing is watched until the column can order versions again.");
        else if (order instanceof Orders)
            conditions.resumed(tenant, g.name(), WorkflowConditions.ORDER, "spec.on.order " + g.order() + " orders again");
        return order;
    }

    /**
     * Each key's current version on an ingest table, as a {@code DISTINCT ON} statement over {@code columns} plus an
     * {@code unreadable} flag. Without an order, the last version ingested. With one, the greatest order value, and
     * ingestion breaks its ties. A version the order cannot place sorts FIRST, so the row picked carries
     * {@code unreadable = true} exactly when its key has one: one sort, and a bad date never makes an older state
     * current. {@code id DESC} is not decoration: one ingest chunk shares one {@code _ingested_at}.
     * A hybrid table has no {@code _ingested_at}: its {@code indexed_at}, the time the row last changed (#903), stands
     * in for it (#1816); the caller names the column, from the registry (#1820).
     *
     * <p>{@code DISTINCT ON} and {@code ORDER BY} name the table's columns qualified: there, a bare name is an output
     * alias first, and a source column may well be called {@code x0} or {@code v}, like one of {@code columns}' aliases.
     */
    static String currentOnTable(OrderColumn order, String quotedOrder, String quotedKey, String columns, String table,
                                 String writtenAt, String where) {
        String t = quoteTable(table);
        String key = t + "." + quotedKey;
        String from = " FROM " + t + where;
        String ingestion = t + "." + writtenAt + " DESC, " + t + ".id DESC";
        if (order == null)
            return "SELECT DISTINCT ON (" + key + ") " + columns + ", false AS unreadable" + from
                + " ORDER BY " + key + ", " + ingestion;
        String o = order.expression(t + "." + quotedOrder);
        return "SELECT DISTINCT ON (" + key + ") " + columns + ", (" + o + " IS NULL) AS unreadable" + from
            + " ORDER BY " + key + ", " + o + " DESC NULLS FIRST, " + ingestion;
    }

    /** The kind of a column of this data type, null when it cannot order versions (or is not there). */
    static OrderColumn of(String dataType) {
        return dataType == null ? null : BY_TYPE.get(dataType);
    }

    /** Why {@code column} cannot order the versions of {@code table}, null when it can. */
    static String problem(String table, String column, String dataType) {
        if (dataType == null) return "spec.on.order " + column + " is not a column of " + table;
        if (of(dataType) == null)
            return "spec.on.order " + column + " is " + dataType + ": a date, a timestamp, a number, or ISO 8601 "
                + "text with an offset can order versions";
        return null;
    }

    /** A sortable value of {@code quotedColumn}, NULL when it has none: not finite, or text that is no instant. */
    String expression(String quotedColumn) {
        String c = quotedColumn;
        return switch (this) {
            case INTEGER -> c;
            case DATE, TIMESTAMP, TIMESTAMPTZ -> "(CASE WHEN isfinite(" + c + ") THEN " + c + " END)";
            case DECIMAL -> "(CASE WHEN " + c + " NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)"
                + " THEN " + c + " END)";
            case FLOAT -> "(CASE WHEN " + c + " NOT IN ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)"
                + " THEN " + c + " END)";
            case TEXT -> "(CASE WHEN " + c + "::text ~ '" + INSTANT_RX + "' AND pg_input_is_valid(" + c
                + "::text, 'timestamptz') THEN " + c + "::text::timestamptz END)";
        };
    }
}
