package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewResolverTest {

    private SchemaCard clientsCard() {
        var t = new SchemaCard.Table("connector.v_customer", "Customer", List.of(
                new SchemaCard.Column("customer_code", "text", "Code"),
                new SchemaCard.Column("name", "text", "Name"),
                new SchemaCard.Column("city", "text", "City")));
        return new SchemaCard("clients", List.of(t));
    }

    @Test
    void resolvesDeclaredColumnsWithCardLabels() {
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("customer_code", "name"));
        ResolvedView rv = new ViewResolver().resolve(m, clientsCard());

        assertThat(rv.title()).isEqualTo("Clients");
        assertThat(rv.table()).isEqualTo("connector.v_customer");
        assertThat(rv.columns()).extracting(ResolvedView.Column::name).containsExactly("customer_code", "name");
        assertThat(rv.columns()).extracting(ResolvedView.Column::label).containsExactly("Code", "Name");
        assertThat(rv.columns()).extracting(ResolvedView.Column::pgType).containsExactly("text", "text");
    }

    @Test
    void refusesAColumnNotInTheCard() {
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("customer_code", "nonexistent_col"));
        assertThatThrownBy(() -> new ViewResolver().resolve(m, clientsCard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent_col");
    }

    @Test
    void unknownColumnMessageUsesViewVocabularyNotSource() {
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("nonexistent_col"));
        assertThatThrownBy(() -> new ViewResolver().resolve(m, clientsCard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fix the column name")   // actionable, View-native
                .hasMessageNotContaining("source");             // Entity vocabulary — a View has none
    }

    @Test
    void derivesTitleFromTheTableLabelWhenManifestOmitsIt() {
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", null,
                List.of("name"));
        assertThat(new ViewResolver().resolve(m, clientsCard()).title()).isEqualTo("Customer");
    }

    @Test
    void infersTheSingleTableWhenManifestOmitsTable() {
        var m = new ViewManifest("clients-desk", "clients", null, "Clients", List.of("name"));
        ResolvedView rv = new ViewResolver().resolve(m, clientsCard());   // one-table card → inferred
        assertThat(rv.columns()).extracting(ResolvedView.Column::name).containsExactly("name");
    }

    @Test
    void refusesWhenManifestOmitsTableAndCardHasMultipleTables() {
        var orders = new SchemaCard.Table("connector.v_order", "Order", List.of(
                new SchemaCard.Column("order_code", "text", "Code")));
        var twoTableCard = new SchemaCard("clients",
                List.of(clientsCard().tables().get(0), orders));
        var m = new ViewManifest("clients-desk", "clients", null, "Clients", List.of("name"));
        assertThatThrownBy(() -> new ViewResolver().resolve(m, twoTableCard))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table:");   // must name one via table:
    }
}
