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
    List<String>        warnings
) {
    public record PromoteColumn(String name, String type, int inferredFromSamples) {}
}
