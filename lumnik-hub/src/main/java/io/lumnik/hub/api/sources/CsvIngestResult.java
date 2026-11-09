package io.lumnik.hub.api.sources;

import java.util.List;
import java.util.UUID;

/**
 * Summary returned by {@code POST /api/platform/sources/csv/ingest}.
 */
public record CsvIngestResult(
    UUID   connectorId,
    UUID   endpointId,
    String tableName,
    int    rowsRead,
    int    rowsWritten,
    int    rowsSkipped,
    List<PromoteColumn> promoteColumns,
    List<String>        warnings,
    /** Tags the table is askable under after this ingest (scope first). */
    List<String>        askableScopes,
    /** Whether this build carries the chat door (the seam resolves a sink). */
    boolean             chatAvailable,
    /** True when an existing one-shot connector took this ingest as one more breath. */
    boolean             refreshed,
    /** What each file of a multi-file batch became; empty for one file (#1920). */
    List<FileResult>    files
) {
    /** One file of a batch: its own table, rows and columns. */
    public record FileResult(String sourceFile, String tableName, int rowsWritten, List<PromoteColumn> promoteColumns,
                             boolean refreshed) {}
    public record PromoteColumn(String name, String type, int inferredFromSamples) {}
}
