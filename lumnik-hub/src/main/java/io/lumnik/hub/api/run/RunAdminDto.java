package io.lumnik.hub.api.run;

import io.lumnik.hub.connector.domain.ConnectorRun;
import java.time.Instant;
import java.util.UUID;

public record RunAdminDto(
    UUID id,
    UUID connectorId,
    String connectorName,
    UUID endpointId,
    String endpointName,
    String status,
    Instant startedAt,
    Instant endedAt,
    long recordsIn,
    long recordsOut,
    long recordsSkipped,
    long cellErrors,
    /**
     * Rows removed because the source no longer holds them (#1195), or older versions of a key (#1196):
     * outside the conservation identity.
     */
    long recordsRemoved,
    String triggeredBy,
    int chunkCount,
    String errorMessage,
    long deadLetterCount
) {
  /**
   * connectorName may be null (connector since deleted) — the ledger is for human
   * diagnosis, so the name rides along whenever it exists; clients fall back to
   * the id when it doesn't.
   *
   * <p>Same rule one level down for the endpoint, which is what tells two rows of the same run
   * apart: a connector fans out over its endpoints and writes one row per endpoint, so a two-table
   * jdbc connector logs two rows that differ only in their counters until the endpoint is named.
   * Both endpoint fields are null together for a run that has no endpoint at all — a row predating
   * endpoints, or one whose endpoint was hard-deleted ({@code ON DELETE SET NULL}, V13).
   */
  public static RunAdminDto from(ConnectorRun r, long deadLetterCount,
                                 String connectorName, String endpointName) {
    return new RunAdminDto(
        r.getId(), r.getConnectorId(), connectorName,
        r.getEndpointId(), endpointName, r.getStatus(),
        r.getStartedAt(), r.getEndedAt(),
        r.getRecordsIn(), r.getRecordsOut(), r.getRecordsSkipped(), r.getCellErrors(),
        r.getRecordsRemoved(), r.getTriggeredBy(), r.getChunkCount(),
        r.getErrorMessage(), deadLetterCount
    );
  }
}
