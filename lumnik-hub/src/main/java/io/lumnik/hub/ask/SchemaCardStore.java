package io.lumnik.hub.ask;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.error.Throwables;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.NoSchemaException;
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

    /** The scope has nothing left to describe: no card, rather than one naming nothing (#1188). */
    public void delete(long tenantId, String scope) {
        Jdbc.update(adminDs, "SchemaCardStore.delete",
            "DELETE FROM connector.schema_card WHERE tenant_id = ? AND scope = ?", ps -> {
                ps.setLong(1, tenantId);
                ps.setString(2, scope);
            });
    }

    public Optional<SchemaCard> load(long tenantId, String scope) {
        String sql = "SELECT card::text FROM connector.schema_card " +
                     "WHERE tenant_id = ? AND scope = ?";
        return Jdbc.queryOne(adminDs, "SchemaCardStore.load", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
        }, rs -> rs.getString(1)).map(json -> readCard(scope, json));
    }

    /**
     * Is this table advertised by some OTHER scope's card? Asked of the stored JSON directly —
     * a containment test on {@code card->'tables'} — because the answer is one row or none and
     * deserializing every card of the tenant to find out would be a page of work for a boolean.
     *
     * <p>It exists for the apply doors: a scope-bound integrator may legitimately declare over a
     * table no card knows yet (declaring before ingesting is a supported order of operations), but
     * never over one that belongs to a métier they do not hold. Callers must first check their OWN
     * card — a table may appear in several scopes, and this answers about the others only.
     */
    public Optional<String> findOtherScopeAdvertising(long tenantId, String scope, String table) {
        String sql = "SELECT scope FROM connector.schema_card "
                   + "WHERE tenant_id = ? AND scope <> ? "
                   + "  AND card -> 'tables' @> jsonb_build_array(jsonb_build_object('name', ?::text)) "
                   + "ORDER BY scope LIMIT 1";
        return Jdbc.queryOne(adminDs, "SchemaCardStore.findOtherScopeAdvertising", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
            ps.setString(3, table);
        }, rs -> rs.getString(1));
    }

    /**
     * Deserialize OUTSIDE {@link Jdbc#queryOne}, on purpose: that helper catches {@code Exception} and
     * rethrows a plain {@code RuntimeException}, so a typed refusal raised inside its row mapper would
     * be wrapped away and reach the door as an anonymous 500 — the very outcome this exists to avoid.
     *
     * <p>This is the only unvalidated entry for a card: a row edited by hand, or written by an older
     * shape, can carry a name no builder could produce, and every reader of a card dereferences that
     * name. {@code SchemaCard} refuses such a name at construction, which is where Jackson lands, and
     * the refusal becomes the same typed exception the chat door already renders as a 409.
     */
    private static SchemaCard readCard(String scope, String json) {
        try {
            return M.readValue(json, SchemaCard.class);
        } catch (JacksonException e) {
            throw NoSchemaException.unusable(scope, rootCauseMessage(e));
        }
    }

    /** Jackson wraps a constructor refusal; the asker needs the sentence that named the problem. */
    static String rootCauseMessage(Throwable t) {
        return Throwables.causeChain(t).getLast().getMessage();
    }
}
