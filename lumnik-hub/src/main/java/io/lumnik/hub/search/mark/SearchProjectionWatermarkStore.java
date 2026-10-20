package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads and advances {@code core.search_projection_mark_watermark} (V56, design §3), the run-level
 * ingestion watermark for D8(a): one row per {@code (tenant_id, table_name)}, admin datasource —
 * cross-tenant discovery, like {@code ConnectorScheduler}.
 *
 * <p>{@link #watermark} returns empty rather than a magic sentinel for "no row yet"; callers treat
 * empty as "-infinity" themselves (design §5). Both methods take an explicit, already-open
 * {@link Connection}: every caller of this store runs inside its own transaction already (the run
 * hook's scan-then-mark-then-advance pass, or the reconciliation sweep), so no own-transaction
 * convenience overload is needed here.
 */
@ApplicationScoped
public class SearchProjectionWatermarkStore {

    @Inject @DataSource("admin") AgroalDataSource ds;

    public Optional<Instant> watermark(Connection c, long tenantId, String tableName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT watermark FROM core.search_projection_mark_watermark "
              + "WHERE tenant_id = ? AND table_name = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(Jdbc.instant(rs, 1));
            }
        }
    }

    /**
     * Advances the watermark to {@code to}, never backwards: a race between two scans of the same
     * table must not regress an already-advanced watermark.
     */
    public void advance(Connection c, long tenantId, String tableName, Instant to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core.search_projection_mark_watermark (tenant_id, table_name, watermark) "
              + "VALUES (?, ?, ?) "
              + "ON CONFLICT (tenant_id, table_name) DO UPDATE SET watermark = excluded.watermark "
              + "WHERE core.search_projection_mark_watermark.watermark < excluded.watermark")) {
            ps.setLong(1, tenantId);
            ps.setString(2, tableName);
            ps.setTimestamp(3, Timestamp.from(to));
            ps.executeUpdate();
        }
    }
}
