package io.lumnik.hub.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, card.scope());
            ps.setString(3, sourceType);
            ps.setString(4, M.writeValueAsString(card));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("SchemaCardStore.save: " + e.getMessage(), e);
        }
    }

    public Optional<SchemaCard> load(long tenantId, String scope) {
        String sql = "SELECT card::text FROM connector.schema_card " +
                     "WHERE tenant_id = ? AND scope = ?";
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(M.readValue(rs.getString(1), SchemaCard.class));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SchemaCardStore.load: " + e.getMessage(), e);
        }
        return Optional.empty();
    }
}
