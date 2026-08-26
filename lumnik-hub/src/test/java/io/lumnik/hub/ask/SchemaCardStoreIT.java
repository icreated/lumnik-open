package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaCardStoreIT {

    @Inject SchemaCardStore store;

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
}
