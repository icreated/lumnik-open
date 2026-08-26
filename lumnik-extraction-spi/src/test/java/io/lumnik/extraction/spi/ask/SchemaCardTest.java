package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaCardTest {

    SchemaCard card() {
        return new SchemaCard("atelier", List.of(
            new SchemaCard.Table("c_order", "Sales Order", List.of(
                new SchemaCard.Column("documentno", "text", "Document No"),
                new SchemaCard.Column("custcol_47", "text", "N° de chantier")
            ))
        ));
    }

    @Test
    void rendersTablesAndBusinessLabelsForThePrompt() {
        String p = card().toPrompt();
        assertThat(p).contains("c_order");
        assertThat(p).contains("Sales Order");
        assertThat(p).contains("custcol_47");
        assertThat(p).contains("N° de chantier");      // the moat: custom column labelled
        assertThat(p).contains("documentno");
    }

    @Test
    void listsAllowedTableNames() {
        assertThat(card().tableNames()).containsExactly("c_order");
    }
}
