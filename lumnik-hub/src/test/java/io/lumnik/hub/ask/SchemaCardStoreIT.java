package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.NoSchemaException;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class SchemaCardStoreIT {

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void savesAndLoadsByScope() {
        long tenant = 4242L;
        SchemaCard card = new SchemaCard("atelier", List.of(
            new SchemaCard.Table("c_order", "Sales Order",
                List.of(new SchemaCard.Column("custcol_47", "text", "N° de chantier")))));

        store.save(tenant, "legacy-erp", card);
        Optional<SchemaCard> loaded = store.load(tenant, "atelier");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().tableNames()).containsExactly("c_order");
        assertThat(loaded.get().toPrompt()).contains("N° de chantier");
    }

    @Test
    void loadMissingScopeReturnsEmpty() {
        assertThat(store.load(4242L, "does-not-exist")).isEmpty();
    }

    /**
     * The unvalidated path. `SchemaCardStore` deserializes raw JSONB, so a corrupt or hand-edited row
     * is the one input that can carry a name no builder could write — and every reader of a card
     * dereferences that name. It used to reach them: the prompt rendered the literal `null`, the model
     * was called and paid for, and the crash landed in `LeakGuard` or `UndocumentedColumnGuard` as an
     * anonymous 500. The record refuses such a name now, and the store turns that refusal into the same
     * TYPED exception the chat door already renders as an honest 409.
     */
    @Test
    void aCorruptStoredCardIsRefusedByNameRatherThanCrashingLater() {
        long tenant = 4243L;
        insertRawCard(tenant, "corrupt-scope",
            "{\"scope\":\"corrupt-scope\",\"tables\":[{\"name\":\"connector.t_x\",\"label\":\"x\","
                + "\"columns\":[{\"name\":null,\"pgType\":\"text\",\"label\":\"x\"}]}]}");

        assertThatThrownBy(() -> store.load(tenant, "corrupt-scope"))
            .as("a card that cannot be read is owed a refusal that names the scope and the cause")
            .isInstanceOf(NoSchemaException.class)
            .hasMessageContaining("corrupt-scope")
            .hasMessageContaining("column name")
            .hasMessageContaining("discovery");
    }

    /** Writes a card as raw JSONB, bypassing {@code save} — the only way to make an unreadable row. */
    private void insertRawCard(long tenant, String scope, String json) {
        try (var c = adminDs.getConnection(); var st = c.prepareStatement(
                "INSERT INTO connector.schema_card (tenant_id, scope, source_type, card, discovered) "
                    + "VALUES (?, ?, 'test', ?::jsonb, true) "
                    + "ON CONFLICT (tenant_id, scope) DO UPDATE SET card = EXCLUDED.card")) {
            st.setLong(1, tenant);
            st.setString(2, scope);
            st.setString(3, json);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seeding a corrupt card failed", e);
        }
    }
}
