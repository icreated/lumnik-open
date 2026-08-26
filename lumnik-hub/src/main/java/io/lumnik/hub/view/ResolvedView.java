package io.lumnik.hub.view;

import java.util.List;

/** A kind:View resolved against a SchemaCard: the title, the backing table FQN, and the labelled columns to render. */
public record ResolvedView(String title, String table, List<Column> columns) {
    public record Column(String name, String label, String pgType) {}
}
