package io.lumnik.hub.export.format;

import io.lumnik.hub.export.ExportManifest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Compiles a manifest's {@code spec.columns[i].format} lists (#1090) — at the apply door, and again
 * by every run from the stored manifest. Knows no concrete kind: the factories are found by
 * {@link ColumnFormatterFactory#kind()}, like {@code FileDestinationRegistry} finds destinations.
 */
@ApplicationScoped
public class ColumnFormatterRegistry {

    private final Iterable<ColumnFormatterFactory> factories;

    @Inject
    public ColumnFormatterRegistry(Instance<ColumnFormatterFactory> factories) {
        this.factories = factories;
    }

    ColumnFormatterRegistry(List<ColumnFormatterFactory> factories) {
        this.factories = factories;
    }

    /**
     * One formatter per exported column, in {@code spec.columns} order: {@link
     * ColumnFormatter#IDENTITY} for a column with none, else its steps composed top to bottom. A
     * refusal is an {@link IllegalArgumentException} naming the step's path.
     */
    public List<ColumnFormatter> compile(ExportManifest manifest) {
        List<ColumnFormatter> out = new ArrayList<>();
        List<ExportManifest.Column> columns = manifest.output();
        for (int i = 0; i < columns.size(); i++) {
            ExportManifest.Column column = columns.get(i);
            List<ColumnFormatter> steps = new ArrayList<>();
            for (int j = 0; j < column.format().size(); j++) {
                ExportManifest.FormatStep step = column.format().get(j);
                String path = "spec.columns[" + i + "].format[" + j + "]";
                steps.add(named(column.name(), step.kind(), compile(step, path)));
            }
            out.add(steps.isEmpty() ? ColumnFormatter.IDENTITY : value -> {
                for (ColumnFormatter step : steps) value = step.format(value);
                return value;
            });
        }
        return List.copyOf(out);
    }

    private ColumnFormatter compile(ExportManifest.FormatStep step, String path) {
        TreeSet<String> known = new TreeSet<>();
        for (ColumnFormatterFactory factory : factories) {
            if (factory.kind().equals(step.kind())) {
                try {
                    return factory.compile(step.params());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(path + "." + step.kind() + ": " + e.getMessage(), e);
                }
            }
            known.add(factory.kind());
        }
        throw new IllegalArgumentException(
            path + ": unknown formatter '" + step.kind() + "' — expected one of " + known);
    }

    /** A run failure names where it happened; the factory's own message says what was expected. */
    private static ColumnFormatter named(String column, String kind, ColumnFormatter step) {
        return value -> {
            try {
                return step.format(value);
            } catch (ColumnFormatException e) {
                throw new ColumnFormatException(
                    "column '" + column + "', formatter '" + kind + "': " + e.getMessage());
            }
        };
    }
}
