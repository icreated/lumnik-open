package io.lumnik.hub.export;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.export.format.ColumnFormatterRegistry;

import java.util.List;
import java.util.Optional;

/**
 * The export's cross-checks against its entity — does the entity exist, is spec.scope one of its
 * declared scopes, is every spec.columns[i] and spec.filter[i].column one of its canonical
 * columns, does every format step compile. One rule shared by the apply door (400) and every run
 * (Failed / definition), so an entity changed after apply fails the run with the very sentence
 * apply would have refused it with.
 */
public final class ExportDefinitionCheck {

    private ExportDefinitionCheck() {}

    /** What a definition resolves to: the first problem, or the shape a snapshot reads and writes. */
    public sealed interface Verdict {}

    /** Worded as the apply door's 400. */
    public record Problem(String sentence) implements Verdict {}

    public record Holds(SnapshotShape shape) implements Verdict {}

    public static Verdict resolve(ExportManifest manifest, EntityManifest entity,
                                  ColumnFormatterRegistry formatters) {
        Optional<String> problem = problem(manifest, entity);
        if (problem.isPresent()) return new Problem(problem.get());
        try {
            return new Holds(SnapshotShape.of(manifest, formatters.compile(manifest)));
        } catch (IllegalArgumentException e) {
            return new Problem(e.getMessage());
        }
    }

    private static Optional<String> problem(ExportManifest manifest, EntityManifest entity) {
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
        for (int i = 0; i < manifest.filter().size(); i++) {
            String col = manifest.filter().get(i).column();
            if (!entityColumns.contains(col)) {
                return Optional.of("spec.filter[" + i + "].column: '" + col
                    + "' is not a canonical column of entity '" + entity.name() + "'");
            }
        }
        return Optional.empty();
    }
}
