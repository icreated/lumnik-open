package io.lumnik.hub.process;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a Process addresses the one row an event names, and reads it as a map: the consumer reads the row itself, and the
 * fused-row reader derives its Entity's key from it (#1833). One place, so the two cannot disagree on a hybrid table.
 */
final class SourceRows {

    /** The hash column the registry names for a hybrid table, whose row is read by its id too (#1816). */
    static final String HYBRID_HASH = DynamicTableRegistry.HYBRID_HASH_COLUMN;

    private SourceRows() {}

    /**
     * The predicate addressing one row of a table, with the tenant — {@link #binder} binds it. A hybrid table has no index
     * on its hash: its row is found by primary key, and the hash checks that the event names the row's current version.
     * A dynamic table is read by its hash.
     *
     * @param hashColumn one of the registry's two fixed names, never a caller's text: safe to write into the SQL
     */
    static String where(String hashColumn) {
        return HYBRID_HASH.equals(hashColumn)
            ? " WHERE id = ? AND tenant_id = ? AND " + HYBRID_HASH + " = ?"
            : " WHERE " + hashColumn + " = ? AND tenant_id = ? LIMIT 1";
    }

    /** @param rowId the hybrid row's id, {@code null} for a dynamic table */
    static Jdbc.Binder binder(Long rowId, Long tenantId, String rowHash) {
        return ps -> {
            if (rowId == null) {
                ps.setString(1, rowHash);
                ps.setLong(2, tenantId);
            } else {
                ps.setLong(1, rowId);
                ps.setLong(2, tenantId);
                ps.setString(3, rowHash);
            }
        };
    }

    /** The current row of {@code rs}, every column by its label. */
    static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) out.put(md.getColumnLabel(i), rs.getObject(i));
        return out;
    }
}
