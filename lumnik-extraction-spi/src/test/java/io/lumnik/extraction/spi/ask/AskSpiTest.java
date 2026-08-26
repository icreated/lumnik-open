package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AskSpiTest {

    @Test
    void askPortContractReturnsAskResult() {
        AskPort stub = (tenantId, scope, question) ->
            new AskResult("2 rows", "SELECT 1 LIMIT 500",
                List.of(Map.of("documentno", "SO-1")), 1);

        AskResult r = stub.ask(1L, "atelier", "list orders");

        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.sql()).contains("LIMIT");
        assertThat(r.rows().get(0)).containsKey("documentno");
    }

    @Test
    void schemaLabelProviderContractReturnsSchemaCard() {
        SchemaLabelProvider stub = (tenantId, scope) -> new SchemaCard(scope, List.of(
            new SchemaCard.Table("c_order", "Sales Order",
                List.of(new SchemaCard.Column("documentno", "text", "Document No")))));

        SchemaCard card = stub.discover(1L, "atelier");

        assertThat(card.scope()).isEqualTo("atelier");
        assertThat(card.tableNames()).containsExactly("c_order");
        assertThat(stub.sourceType()).isEqualTo("generic");
    }
}
