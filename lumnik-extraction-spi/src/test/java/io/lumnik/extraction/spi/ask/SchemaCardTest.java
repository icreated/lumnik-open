package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * A name is the one field of a card that everything downstream dereferences: two guards fold it to
     * lower case ({@code LeakGuard}, {@code UndocumentedColumnGuard}), five apply-time resolvers compare
     * it, and the renderer quotes it. A {@code null} there is not a degraded card, it is an unusable one
     * — and it used to travel: the render appended the literal {@code null} and the crash landed in a
     * guard, one model call later. Refusing it at construction is the shape that PREVENTS the whole
     * class rather than a null check per reader.
     *
     * <p>No code path produces one — {@code connector.dynamic_table.table_name} is NOT NULL and JSON
     * object keys cannot be null — so this closes a corrupt or hand-edited row, which is exactly the
     * input that reaches {@code SchemaCardStore.load} unvalidated.
     */
    @ParameterizedTest(name = "a column named {0} is refused at construction")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aColumnNameThatNamesNothingIsRefused(String name) {
        assertThatThrownBy(() -> new SchemaCard.Column(name, "text", "label"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("column name");
    }

    @ParameterizedTest(name = "a table named {0} is refused at construction")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aTableNameThatNamesNothingIsRefused(String name) {
        assertThatThrownBy(() -> new SchemaCard.Table(name, "label", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("table name");
    }

    /** The label and the grounding stay optional — only the NAME is load-bearing. */
    @Test
    void everythingElseMayStillBeAbsent() {
        var c = new SchemaCard.Column("segment", null, null, null, null, null);
        assertThat(new SchemaCard("s", List.of(new SchemaCard.Table("connector.v", null, List.of(c))))
            .toPrompt()).contains("\"segment\"");
    }
}
