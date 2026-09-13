package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a kind:View (list) manifest against its scope's SchemaCard: every declared column must
 * exist in the card's table (honesty — a view never renders a column that isn't real), and its
 * label/pgType come from the card. Pure: no DB, no HTTP.
 */
public class ViewResolver {

    public ResolvedView resolve(ViewManifest m, SchemaCard card) {
        SchemaCard.Table table = findTable(m, card);
        List<ResolvedView.Column> cols = new ArrayList<>();
        for (String declared : m.columns()) {
            SchemaCard.Column c = table.columns().stream()
                    .filter(col -> col.name().equals(declared))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "column '" + declared + "' is not in the '" + m.scope()
                                    + "' schema for table " + table.name()
                                    + " — fix the column name or add it to the scope's entity"));
            String label = (c.label() != null && !c.label().isBlank()) ? c.label() : c.name();
            cols.add(new ResolvedView.Column(c.name(), label, c.pgType()));
        }
        String title = (m.title() != null && !m.title().isBlank()) ? m.title()
                : (table.label() != null && !table.label().isBlank()) ? table.label() : table.name();
        return new ResolvedView(title, table.name(), cols);
    }

    /**
     * The card table this manifest is about — the same lookup {@link #resolve} performs, exposed
     * so a caller that needs the TABLE before it has final columns does not hand-copy it. The
     * generation agent does: it must know the table to decide whether a filter names a column of
     * it, and that decision changes the column list it then resolves.
     */
    SchemaCard.Table findTable(ViewManifest m, SchemaCard card) {
        if (m.table() != null && !m.table().isBlank()) {
            return card.tables().stream()
                    .filter(t -> t.name().equals(m.table()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "table '" + m.table() + "' is not in the '" + m.scope() + "' scope"));
        }
        if (card.tables().size() == 1) return card.tables().get(0);
        throw new IllegalArgumentException("scope '" + m.scope()
                + "' has " + card.tables().size() + " tables — the manifest must name one via 'table:'");
    }
}
