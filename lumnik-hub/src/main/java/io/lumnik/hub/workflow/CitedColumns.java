package io.lumnik.hub.workflow;

import io.lumnik.common.sql.Jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The columns a dormancy or due decision carries (#1588), and how this tick reads each: the quoted column, or
 * {@code NULL} once the relation no longer has it. Built from the catalog before the statement, so the statement
 * never names a column that is gone; one that went between the two is a 42703 the sweep rebuilds once.
 */
record CitedColumns(List<String> columns, List<String> expressions) {

    static final CitedColumns NONE = new CitedColumns(List.of(), List.of());

    /**
     * Asks the catalog which of {@code wanted} the relation still has. One it lost renders empty and is said once; the
     * decision still goes out, because the alarm matters more than the sentence. A relation that is not there has lost
     * nothing: the statement's 42P01 stands the workflow down, and that is the one thing said. A column no longer
     * wanted is released first ({@link WorkflowConditions#releaseUncited}).
     */
    static CitedColumns of(DataSource readonly, WorkflowConditions conditions, long tenant, WorkflowGraph g,
                           List<String> wanted) {
        conditions.releaseUncited(tenant, g.name(), wanted);
        if (wanted.isEmpty()) return NONE;
        List<String> expressions = new ArrayList<>();
        try (Connection c = readonly.getConnection()) {
            for (String column : wanted) {
                boolean present = WorkflowDataTruth.hasColumn(c, g.table(), column);
                if (!present && !WorkflowDataTruth.relationExists(c, g.table())) return NONE;
                expressions.add(present ? quoteIdent(column) : "NULL");
                if (present)
                    conditions.resumed(tenant, g.name(), WorkflowConditions.context(column),
                        g.table() + " has " + column + " again");
                else
                    conditions.warnOnce(tenant, g.name(), WorkflowConditions.context(column),
                        "cites " + column + ", which " + g.table() + " no longer has: it renders empty");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(Jdbc.failureText("CitedColumns.of", e), e);
        }
        return new CitedColumns(wanted, expressions);
    }

    /** {@code , e0 AS x0, e1 AS x1 …}: the projection a statement selects. */
    String projected() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) out.append(", ").append(expressions.get(i)).append(" AS x").append(i);
        return out.toString();
    }

    /** {@code , s.x0, s.x1 …}: the same columns carried through an outer statement aliased {@code s}. */
    String carried() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) out.append(", s.x").append(i);
        return out.toString();
    }

    /** What a row says of each column, from its {@code first} result column on, in the columns' order. */
    Map<String, Object> read(ResultSet rs, int first) throws SQLException {
        if (columns.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) out.put(columns.get(i), rs.getObject(first + i));
        return out;
    }

    /** What a view row's extra values say of each column, from {@code values[first]} on, in the columns' order. */
    Map<String, Object> read(List<Object> values, int first) {
        if (columns.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) out.put(columns.get(i), values.get(first + i));
        return out;
    }

    /**
     * Runs a sweep's tick, and once more if a column vanished between the catalog read and the statement (SQLSTATE
     * 42703, an entity re-applied in between): the rerun reads the catalog again, so a cited column now gone reads as
     * NULL. Rerunning is safe — the ledgers keep a decision from going out twice. A second failure is the caller's.
     */
    static <T> T rebuiltOnce(Supplier<T> tick) {
        try {
            return tick.get();
        } catch (RuntimeException e) {
            if (!"42703".equals(Jdbc.sqlStateOf(e))) throw e;
            return tick.get();
        }
    }
}
