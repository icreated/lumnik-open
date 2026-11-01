package io.lumnik.hub.ask;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.jdbc.HikariPoolRegistry;
import io.lumnik.hub.connector.jdbc.JdbcConnectionConfig;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SchemaLabelProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Level 2 of discovery: read Application-Dictionary-style labels (AD_COLUMN/AD_TABLE/
 * AD_ELEMENT — a common ERP metadata pattern) from a source database that exposes them.
 * Best-effort — any failure (unreachable, no such AD schema) returns an empty map so
 * discovery keeps the level-1 card. Reads are SELECT-only on the source.
 */
@ApplicationScoped
public class AdLabelProvider implements SchemaLabelProvider {

    private static final Logger LOG = Logger.getLogger(AdLabelProvider.class);

    @Inject HikariPoolRegistry poolRegistry;
    @Inject JdbcDialectRegistry dialectRegistry;

    @Override public String sourceType() { return "jdbc-ad"; }

    /** Satisfies the SPI; not the real entry point (DiscoveryService works per source table). */
    @Override public SchemaCard discover(long tenantId, String scope) {
        return new SchemaCard(scope, List.of());
    }

    /** Map of lower(columnName) → business label for the given source table. Empty on any failure. */
    public Map<String,String> labelsForTable(String url, String user, String password, String tableName) {
        Map<String,String> out = new LinkedHashMap<>();
        final JdbcConnectionConfig cc;
        try {
            cc = JdbcConnectionConfig.parse(url, user, password, dialectRegistry);
        } catch (RuntimeException e) {
            LOG.debugf("AdLabelProvider: bad JDBC config: %s", e.getMessage());
            return out;
        }
        String sql =
            "SELECT c.columnname AS columnname, " +
            "       COALESCE(NULLIF(e.name, ''), c.name) AS label " +
            "FROM ad_column c " +
            "JOIN ad_table t ON t.ad_table_id = c.ad_table_id " +
            "LEFT JOIN ad_element e ON e.ad_element_id = c.ad_element_id " +
            "WHERE lower(t.tablename) = lower(?)";
        try {
            DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L,
                dialectRegistry.resolve(cc.dialectId()));
            List<String[]> rows = Jdbc.query(ds, "AdLabelProvider.labelsForTable", sql, ps -> {
                ps.setQueryTimeout(5);
                ps.setString(1, tableName);
            }, rs -> new String[]{ rs.getString("columnname"), rs.getString("label") });
            for (String[] row : rows) {
                String col = row[0], label = row[1];
                if (col != null && label != null && !label.isBlank()) {
                    out.put(col.toLowerCase(Locale.ROOT), label);
                }
            }
        } catch (Exception e) {
            LOG.warnf("AdLabelProvider: AD read failed for %s (%s) — labels skipped: %s",
                      tableName, cc.url(), e.getMessage());
            return new LinkedHashMap<>();
        }
        return out;
    }
}
