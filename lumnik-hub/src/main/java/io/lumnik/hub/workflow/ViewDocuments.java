package io.lumnik.hub.workflow;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.sql.TenantRlsScope;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;

/**
 * A fused view's documents (#1588). A view has no ingest order and may hold several rows per workflow key (the
 * freezer view has one row per event), so a document's current version is its rank-1 rows under
 * {@code spec.on.order}. The view is ranked once per sweep and handed over a page at a time (#1595); a key is never cut
 * across two pages, so an ambiguity is never missed at a page boundary. Read on the readonly datasource under the
 * caller's tenant: the view is {@code security_invoker}, and the RLS of the tables it fuses confines it.
 */
final class ViewDocuments {

    /** One rank-1 row: its state as written, whether its key has a version the order cannot place, and the values
     *  of the caller's extra expressions, in their order. */
    record Row(String raw, boolean unreadable, List<Object> extra) {}

    record Document(String key, List<Row> top) {}

    sealed interface Verdict permits Current, Unreadable, Ambiguous {}

    record Current(Row row) implements Verdict {}

    record Unreadable() implements Verdict {}

    record Ambiguous() implements Verdict {}

    private ViewDocuments() {}

    /**
     * Walks the view's documents, {@code rows} rows at a time (#1595). The view is read ONCE per sweep: one ranked
     * statement, declared {@code WITH HOLD} in a short transaction of the connection's own under the tenant, is computed
     * at its commit — the view's RLS applies then, and its locks end there — and fetched afterwards on the same
     * connection, with nothing left open while the caller reconciles. A key's rows are contiguous; the last key of a
     * fetch waits for the next one, so a key is never cut across two pages. Each {@code extra} entry is a ready SQL
     * expression the caller has already quoted ({@code quoteIdent(column)}, or {@code NULL}).
     *
     * <p>Before #1595 each page re-read the view from its bound: about N²/2000 key reads per sweep, 87 s for 100,000
     * keys ({@code ViewSweepBenchmark}).
     */
    static void forEachPage(DataSource readonly, long tenant, WorkflowGraph g, OrderColumn order,
                            boolean blankIsAVersion, List<String> extra, int rows, Consumer<List<Document>> page) {
        // A name of its own per sweep: a cursor that failed to close never collides with the next one.
        String cursor = "lumnik_view_" + Long.toHexString(System.nanoTime());
        try (Connection c = readonly.getConnection()) {
            // A failed return to autocommit after the commit throws too: the walk stops before a FETCH that would hold
            // a transaction for the whole sweep.
            Jdbc.transaction(c, "ViewDocuments.forEachPage", tx -> {
                TenantRlsScope.applyToOwnTransaction(tx, tenant);
                String sql = currentVersions(g, order, blankIsAVersion, extra, identity(tx, g.table()));
                try (Statement st = tx.createStatement()) {
                    st.execute("DECLARE " + cursor + " CURSOR WITH HOLD FOR " + sql);
                }
                return null;
            });
            try {
                fetch(c, cursor, extra.size(), rows, page);
            } finally {
                // Best-effort: the connection is closed next, and a failure here must not hide the one above.
                try (Statement st = c.createStatement()) {
                    st.execute("CLOSE " + cursor);
                } catch (SQLException close) {
                    // the cursor goes with the session
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(Jdbc.failureText("ViewDocuments.forEachPage", e), e);
        }
    }

    private static void fetch(Connection c, String cursor, int extras, int limit, Consumer<List<Document>> page)
            throws SQLException {
        String key = null;
        List<Row> rows = new ArrayList<>();
        int fetched;
        do {
            List<Document> docs = new ArrayList<>();
            fetched = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("FETCH FORWARD " + limit + " FROM " + cursor)) {
                while (rs.next()) {
                    fetched++;
                    String k = rs.getString(1);
                    if (key != null && !key.equals(k)) {
                        docs.add(new Document(key, rows));
                        rows = new ArrayList<>();
                    }
                    key = k;
                    List<Object> values = new ArrayList<>(extras);
                    for (int i = 0; i < extras; i++) values.add(rs.getObject(4 + i));
                    rows.add(new Row(rs.getString(2), rs.getBoolean(3), values));
                }
            }
            if (fetched < limit && key != null) {   // the end: the last key is whole
                docs.add(new Document(key, rows));
                key = null;
            }
            if (!docs.isEmpty()) page.accept(docs);
        } while (fetched == limit);
    }

    /**
     * Every key's rank-1 rows under the order, by key then identity. {@code blankIsAVersion} is the sweep's own rule,
     * the one its table path follows: dormancy skips a row with a blank status, the due sweep counts it as the version
     * it is.
     */
    static String currentVersions(WorkflowGraph g, OrderColumn order, boolean blankIsAVersion, List<String> extra,
                                  String identity) {
        String key = quoteIdent(g.key());
        String column = quoteIdent(g.column());
        StringBuilder projected = new StringBuilder();
        StringBuilder carried = new StringBuilder();
        for (int i = 0; i < extra.size(); i++) {
            projected.append(", ").append(extra.get(i)).append(" AS x").append(i);
            carried.append(", x").append(i);
        }
        return "WITH versions AS (SELECT " + key + "::text AS k, " + column + "::text AS v, "
            + order.expression(quoteIdent(g.order())) + " AS o, " + identity + " AS ident" + projected
            + " FROM " + quoteTable(g.table()) + " WHERE " + key + " IS NOT NULL AND btrim(" + key + "::text) <> ''"
            + (blankIsAVersion ? "" : " AND " + column + " IS NOT NULL AND btrim(" + column + "::text) <> ''") + "), "
            + "ranked AS (SELECT k, v, ident" + carried + ", bool_or(o IS NULL) OVER (PARTITION BY k) AS unreadable, "
            + "rank() OVER (PARTITION BY k ORDER BY o DESC NULLS LAST) AS r FROM versions) "
            + "SELECT k, v, unreadable" + carried + " FROM ranked WHERE r = 1 ORDER BY k, ident";
    }

    /**
     * Unreadable before anything else. Then one row, or rows that agree, are current: the first one, the smallest
     * identity, a deterministic pick and not "the latest". Rows that disagree are ambiguous. What "agree" means is
     * the caller's: the resolved state for dormancy, the state and the deadline for a due date.
     */
    static Verdict decide(Document d, Function<Row, Object> agreement) {
        if (d.top().stream().anyMatch(Row::unreadable)) return new Unreadable();
        Object first = agreement.apply(d.top().get(0));
        for (Row r : d.top()) if (!Objects.equals(first, agreement.apply(r))) return new Ambiguous();
        return new Current(d.top().get(0));
    }

    /** The view's canonical identity: {@code _canonical_key} for a match: entity, else the resolution pair (an
     *  unresolved row has no {@code _entity_id}; {@code _handle} is its address). */
    private static String identity(Connection c, String view) throws SQLException {
        return WorkflowDataTruth.columnType(c, view, "_canonical_key") != null
            ? quoteIdent("_canonical_key")
            : "coalesce(" + quoteIdent("_entity_id") + "::text, " + quoteIdent("_handle") + ")";
    }
}
