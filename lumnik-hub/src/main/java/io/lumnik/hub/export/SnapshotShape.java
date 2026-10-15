package io.lumnik.hub.export;

import io.lumnik.hub.export.format.ColumnFormatter;

import java.util.Collections;
import java.util.List;

/**
 * What a snapshot reads and how the file shows it (#1090): the canonical {@code columns} read, the
 * {@code headers} written, one compiled {@code formatters} entry per column, and the {@code filter}
 * pushed into the query. The one-shot download is {@link #canonical}: every column as it reads.
 */
public record SnapshotShape(List<String> columns, List<String> headers, List<ColumnFormatter> formatters,
                            List<ExportManifest.Filter> filter) {

    /** The three per-column lists are read by position: they must line up. */
    public SnapshotShape {
        if (headers.size() != columns.size() || formatters.size() != columns.size())
            throw new IllegalArgumentException("headers and formatters must have one entry per column");
    }

    public static SnapshotShape canonical(List<String> columns) {
        return new SnapshotShape(columns, columns,
            Collections.nCopies(columns.size(), ColumnFormatter.IDENTITY), List.of());
    }

    public static SnapshotShape of(ExportManifest manifest, List<ColumnFormatter> formatters) {
        return new SnapshotShape(manifest.columns(),
            manifest.output().stream().map(ExportManifest.Column::header).toList(), formatters, manifest.filter());
    }
}
