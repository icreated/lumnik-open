package io.lumnik.hub.view;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void exposesTheCardsAllowedValuesSoTheUiCanOfferASelection() {
        var t = new SchemaCard.Table("connector.v_customer", "Customer", List.of(
                new SchemaCard.Column("segment", "text", "Segment", null, null, List.of("GOLD", "SILVER")),
                new SchemaCard.Column("name", "text", "Name")));
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("segment", "name"));
        ResolvedView rv = new ViewResolver().resolve(m, new SchemaCard("clients", List.of(t)));

        assertThat(rv.columns().get(0).allowedValues()).containsExactly("GOLD", "SILVER");
        assertThat(rv.columns().get(1).allowedValues()).isEmpty();   // no domain → empty, never null
    }

    @Test
    void theColumnWireShapeCarriesAllowedValues() throws Exception {
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(
                new ResolvedView.Column("segment", "Segment", "text", List.of("GOLD"))));
        assertThat(json.get("allowedValues").get(0).asText()).isEqualTo("GOLD");
        assertThat(json.get("pgType").asText()).isEqualTo("text");
        assertThat(json.get("kind").asText()).isEqualTo("choice");
        assertThat(json.get("operators").toString()).isEqualTo("[\"=\"]");
    }

    @Test
    void theColumnsCarryTheHubsKindAndOperators() {
        var t = new SchemaCard.Table("connector.v_customer", "Customer", List.of(
                new SchemaCard.Column("amount", "numeric", "Amount"),
                new SchemaCard.Column("price", "money", "Price"),
                new SchemaCard.Column("name", "text", "Name")));
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("amount", "price", "name"));
        var cols = new ViewResolver().resolve(m, new SchemaCard("clients", List.of(t))).columns();

        assertThat(cols).extracting(ResolvedView.Column::kind).containsExactly("number", "text", "text");
        assertThat(cols.get(0).operators()).containsExactly("=", ">=", "<=", ">", "<");
        assertThat(cols.get(1).operators()).as("money is never offered an ordering").containsExactly("~", "=");
    }
}
