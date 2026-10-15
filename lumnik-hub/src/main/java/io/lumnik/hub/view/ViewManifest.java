package io.lumnik.hub.view;

import java.util.List;

/**
 * A hand-written kind:View (list) manifest. `table` null = the scope's single table; `title` null = derive it.
 * {@code links} (#1050) is empty when the view declares none.
 */
public record ViewManifest(String name, String scope, String table, String title, List<String> columns,
                           List<ViewLink> links) {

    public ViewManifest {
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** A view with no links — the shape every manifest had before #1050. */
    public ViewManifest(String name, String scope, String table, String title, List<String> columns) {
        this(name, scope, table, title, columns, List.of());
    }
}
