package io.lumnik.hub.view;

import java.util.List;

/**
 * A hand-written kind:View (list) manifest. `table` null = the scope's single table; `title` null = derive it.
 * {@code links} (#1050) is empty when the view declares none. {@code mapPoint} (#2057) names the entity point the
 * view is drawn on in the PWA's map; null when the view asks for no map.
 */
public record ViewManifest(String name, String scope, String table, String title, List<String> columns,
                           List<ViewLink> links, String mapPoint) {

    public ViewManifest {
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** A view with no map — the shape every manifest had before #2057. */
    public ViewManifest(String name, String scope, String table, String title, List<String> columns,
                        List<ViewLink> links) {
        this(name, scope, table, title, columns, links, null);
    }

    /** A view with no links — the shape every manifest had before #1050. */
    public ViewManifest(String name, String scope, String table, String title, List<String> columns) {
        this(name, scope, table, title, columns, List.of());
    }
}
