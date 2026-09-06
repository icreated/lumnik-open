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
    boolean             refreshed
) {
    public record PromoteColumn(String name, String type, int inferredFromSamples) {}
}
