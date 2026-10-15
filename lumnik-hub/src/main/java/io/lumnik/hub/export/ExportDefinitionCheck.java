package io.lumnik.hub.export;

import io.lumnik.hub.entity.EntityManifest;

import java.util.List;
import java.util.Optional;

/**
 * The export's cross-checks against its entity — does the entity exist, is spec.scope one of its
 * declared scopes, is every spec.columns[i] one of its canonical columns. One rule shared by the
 * apply door (400) and every run (Failed / definition), so an entity changed after apply fails the
 * run with the very sentence apply would have refused it with.
 */
public final class ExportDefinitionCheck {

    private ExportDefinitionCheck() {}

    /** The first problem, worded as the apply door's 400; empty when the definition holds. */
    public static Optional<String> problem(ExportManifest manifest, EntityManifest entity) {
        if (entity == null) {
            return Optional.of("spec.source.entity: no entity named '" + manifest.entity() + "'");
        }
        if (!entity.scopes().contains(manifest.scope())) {
            return Optional.of("spec.scope '" + manifest.scope()
                + "' is not one of the declared scopes of entity '" + entity.name() + "': "
                + entity.scopes());
        }
        List<String> entityColumns = entity.canonicalColumns();
        for (String col : manifest.columns()) {
            if (!entityColumns.contains(col)) {
                return Optional.of("spec.columns: '" + col + "' is not a canonical column of entity '"
                    + entity.name() + "'");
            }
        }
        return Optional.empty();
    }
}
