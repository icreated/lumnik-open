package io.lumnik.hub.search;

import io.lumnik.hub.entity.EntityManifest;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The cross-manifest half of #669's allowlist: a SearchIndex may only name canonical columns of the
 * Entity it references. Asked at apply, and again whenever that Entity is re-applied — a column the
 * entity no longer has turns the SearchIndex invalid instead of silently projecting nothing.
 */
final class SearchIndexDefinitionCheck {

    private SearchIndexDefinitionCheck() {}

    /** The first problem, addressed by manifest path, or empty when the SearchIndex is valid over {@code entity}. */
    static Optional<String> problem(SearchIndexManifest index, EntityManifest entity) {
        Set<String> canonical = new HashSet<>(entity.canonicalColumns());
        List<SearchIndexManifest.Field> fields = index.fields();
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).name();
            if (!canonical.contains(name))
                return Optional.of("spec.fields[" + i + "].name: '" + name
                    + "' is not a canonical column of entity '" + entity.name() + "'");
        }
        return Optional.empty();
    }
}
