package io.lumnik.hub.api.run;

import io.lumnik.hub.connector.domain.ConnectorRun;
import java.time.Instant;
import java.util.UUID;

public record RunAdminDto(
    UUID id,
    UUID connectorId,
    String connectorName,
    String status,
    Instant startedAt,
    Instant endedAt,
    long recordsIn,
    long recordsOut,
    long recordsSkipped,
    long cellErrors,
    String triggeredBy,
    int chunkCount,
    String errorMessage,
    long deadLetterCount
) {
  /**
   * connectorName may be null (connector since deleted) — the ledger is for human
   * diagnosis, so the name rides along whenever it exists; clients fall back to
   * the id when it doesn't.
   */
  public static RunAdminDto from(ConnectorRun r, long deadLetterCount, String connectorName) {
    return new RunAdminDto(
        r.getId(), r.getConnectorId(), connectorName, r.getStatus(),
        r.getStartedAt(), r.getEndedAt(),
        r.getRecordsIn(), r.getRecordsOut(), r.getRecordsSkipped(), r.getCellErrors(),
        r.getTriggeredBy(), r.getChunkCount(),
        r.getErrorMessage(), deadLetterCount
    );
  }
}
