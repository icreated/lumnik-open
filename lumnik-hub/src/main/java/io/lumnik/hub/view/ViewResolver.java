package io.lumnik.hub.view;

import io.lumnik.common.text.Text;
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
        return resolve(m, findTable(m, card));
    }

    private ResolvedView resolve(ViewManifest m, SchemaCard.Table table) {
        List<ResolvedView.Column> cols = new ArrayList<>();
        for (String declared : m.columns()) {
            SchemaCard.Column c = table.columns().stream()
                    .filter(col -> col.name().equals(declared))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "column '" + declared + "' is not in the '" + m.scope()
                                    + "' schema for table " + table.name()
                                    + " — fix the column name or add it to the scope's entity"));
            String label = Text.present(c.label()) ? c.label() : c.name();
            cols.add(new ResolvedView.Column(c.name(), label, c.pgType(), c.allowedValues()));
        }
        String title = Text.present(m.title()) ? m.title()
                : Text.present(table.label()) ? table.label() : table.name();
        return new ResolvedView(title, table.name(), cols, mapPoint(m, table));
    }

    /**
     * The card's point the view's map names (#2057), or null. A point the card does not carry is not refused here: the
     * card lists usable points only, so a run that retyped a column makes the point vanish, and a saved view must still
     * render as a list. The apply door refuses it ({@link #resolveForApply}); the render reports it.
     */
    static SchemaCard.Point mapPoint(ViewManifest m, SchemaCard.Table table) {
        if (m.mapPoint() == null) return null;
        return table.points().stream().filter(p -> p.name().equals(m.mapPoint())).findFirst().orElse(null);
    }

    /**
     * {@link #resolve}, strict where the render is lenient: the apply door refuses a map on a point the table does not
     * offer, naming the offered ones, and a table whose column would be shadowed by the map's field.
     */
    public ResolvedView resolveForApply(ViewManifest m, SchemaCard card) {
        SchemaCard.Table table = findTable(m, card);
        ResolvedView rv = resolve(m, table);
        requireMapPoint(m, rv, table);
        return rv;
    }

    private static void requireMapPoint(ViewManifest m, ResolvedView rv, SchemaCard.Table table) {
        if (m.mapPoint() != null && rv.map() == null) {
            List<String> offered = table.points().stream().map(SchemaCard.Point::name).toList();
            throw new IllegalArgumentException("spec.map.point: table " + table.name() + " offers no point '"
                + m.mapPoint() + "' (" + (offered.isEmpty() ? "it offers none" : "offered: " + String.join(", ", offered))
                + ") — declare it under the entity's points, with two numeric columns");
        }
        if (m.mapPoint() != null && table.columns().stream().anyMatch(c -> c.name().equals(MapPlacement.FIELD)))
            throw new IllegalArgumentException("spec.map: table " + table.name() + " has a column named '"
                + MapPlacement.FIELD + "', the field a map row carries — rename the column in the entity");
    }

    /**
     * The card table this manifest is about — the same lookup {@link #resolve} performs, exposed
     * so a caller that needs the TABLE before it has final columns does not hand-copy it. The
     * generation agent does: it must know the table to decide whether a filter names a column of
     * it, and that decision changes the column list it then resolves.
     */
    SchemaCard.Table findTable(ViewManifest m, SchemaCard card) {
        if (Text.present(m.table())) {
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
