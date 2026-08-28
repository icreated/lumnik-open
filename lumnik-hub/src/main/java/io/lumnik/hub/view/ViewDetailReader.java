package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;

/**
 * Reads one kind:View record by its first declared column (the k9s identity), read-only and
 * tenant-isolated — the same boundary as the list (SET LOCAL app.current_tenant + empty search_path).
 * Appends the fused-entity `_conflicts` ledger column when the table has one (probed via
 * information_schema). LIMIT 2 so an ambiguous key (first column not unique) is detectable without
 * over-reading. Must run inside the caller's transaction. Key is a bound parameter; identifiers are quoted.
 */
@ApplicationScoped
public class ViewDetailReader {

    private static final int TIMEOUT_S = 5;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    public record DetailRow(List<Object> values, String conflictsJson) {}
    public record DetailRead(boolean ledgerPresent, List<DetailRow> rows) {}

    public DetailRead read(long tenantId, ResolvedView rv, String key) {
        List<ResolvedView.Column> cols = rv.columns();
        String firstCol = cols.get(0).name();
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenantId);
            boolean ledgerPresent = hasConflictsColumn(c, rv.table());

            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(quoteIdent(cols.get(i).name()));
            }
            if (ledgerPresent) sql.append(", ").append(quoteIdent("_conflicts")).append("::text");
            sql.append(" FROM ").append(quoteTable(rv.table()))
               .append(" WHERE ").append(quoteIdent(firstCol)).append("::text = ? LIMIT 2");

            List<DetailRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                ps.setQueryTimeout(TIMEOUT_S);
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<Object> values = new ArrayList<>(cols.size());
                        for (int i = 1; i <= cols.size(); i++) values.add(rs.getObject(i));
                        String conflictsJson = ledgerPresent ? rs.getString(cols.size() + 1) : null;
                        rows.add(new DetailRow(values, conflictsJson));
                    }
                }
            }
            return new DetailRead(ledgerPresent, rows);
        } catch (SQLException e) {
            throw new RuntimeException("ViewDetailReader.read: " + e.getMessage(), e);
        }
    }

    private static boolean hasConflictsColumn(Connection c, String fqn) throws SQLException {
        int dot = fqn.indexOf('.');
        String schema = dot < 0 ? "" : fqn.substring(0, dot);
        String table = dot < 0 ? fqn : fqn.substring(dot + 1);
        String q = "SELECT 1 FROM information_schema.columns "
                 + "WHERE table_schema = ? AND table_name = ? AND column_name = '_conflicts'";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

}
