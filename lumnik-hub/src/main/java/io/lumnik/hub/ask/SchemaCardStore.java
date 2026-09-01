package io.lumnik.hub.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/** Persists/loads SchemaCard JSONB in connector.schema_card via the admin datasource. */
@ApplicationScoped
public class SchemaCardStore {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public void save(long tenantId, String sourceType, SchemaCard card) {
        String sql = "INSERT INTO connector.schema_card " +
            "(tenant_id, scope, source_type, card, discovered) VALUES (?, ?, ?, ?::jsonb, true) " +
            "ON CONFLICT (tenant_id, scope) DO UPDATE SET " +
            "  source_type = EXCLUDED.source_type, card = EXCLUDED.card, " +
            "  discovered = true, updated_at = now()";
        Jdbc.update(adminDs, "SchemaCardStore.save", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, card.scope());
            ps.setString(3, sourceType);
            ps.setString(4, M.writeValueAsString(card));
        });
    }

    public Optional<SchemaCard> load(long tenantId, String scope) {
        String sql = "SELECT card::text FROM connector.schema_card " +
                     "WHERE tenant_id = ? AND scope = ?";
        return Jdbc.queryOne(adminDs, "SchemaCardStore.load", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
        }, rs -> M.readValue(rs.getString(1), SchemaCard.class));
    }
}
