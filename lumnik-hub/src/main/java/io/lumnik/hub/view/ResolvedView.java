package io.lumnik.hub.view;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.TemporalText;

import java.util.ArrayList;
import java.util.List;

/**
 * A kind:View resolved against a SchemaCard: the title, the backing table FQN, and the labelled columns to render.
 * {@code map} (#2057) is the card's point the view is drawn on, null when the view asks for no map or its point is not
 * usable on the card now.
 */
public record ResolvedView(String title, String table, List<Column> columns, SchemaCard.Point map) {

    public ResolvedView(String title, String table, List<Column> columns) {
        this(title, table, columns, null);
    }

    /**
     * The columns a read selects: the declared ones, then the map point's two when the view does not show them. Those
     * two are read for the hub's placement verdict only, and leave the row before it is served ({@link MapPlacement}).
     */
    public List<String> selected() {
        List<String> names = new ArrayList<>(columns.stream().map(Column::name).toList());
        if (map != null) {
            if (!names.contains(map.lat())) names.add(map.lat());
            if (!names.contains(map.lon())) names.add(map.lon());
        }
        return names;
    }

    /**
     * A row value as JSON writes it for this view: a timestamp as its UTC instant (#1314), and over an entity a
     * number as its text too, as every entity read says it (#1338) — a table's numbers stay JSON numbers.
     */
    public Object wire(Object value) {
        return EntityViewCompiler.entityNameFromView(table) != null ? TemporalText.entityWire(value) : TemporalText.wire(value);
    }
    /**
     * {@code allowedValues} is the card's declared domain, empty (never null) when there is none.
     * {@code kind} and {@code operators} are the hub's answer to what a user may do with the column
     * ({@link ColumnKind}); they are derived on the way out, so nothing can announce what the
     * engine cannot evaluate.
     */
    public record Column(String name, String label, String pgType, List<String> allowedValues) {
        public Column {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }

        public Column(String name, String label, String pgType) {
            this(name, label, pgType, List.of());
        }

        @JsonProperty("kind")
        public String kind() {
            return ColumnKind.of(pgType, allowedValues).wire();
        }

        @JsonProperty("operators")
        public List<String> operators() {
            return ColumnKind.of(pgType, allowedValues).operators();
        }
    }
}
