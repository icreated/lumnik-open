package io.lumnik.hub.api.dlq;

import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import java.time.Instant;
import java.util.UUID;

public record DlqAdminDto(
    UUID id,
    UUID connectorId,
    String connectorName,
    UUID runId,
    String skipReason,
    String errorMessage,
    int attempts,
    String resolution,
    Instant capturedAt,
    String payload,
    String skipDetail
) {
  /** connectorName may be null (hub could not resolve it); clients fall back to the id, as for runs. */
  public static DlqAdminDto from(ConnectorDeadLetter d, String connectorName) {
    return new DlqAdminDto(
        d.getId(), d.getConnectorId(), connectorName, d.getRunId(),
        d.getSkipReason(), d.getErrorMessage(),
        d.getAttempts(), d.getResolution(),
        d.getCapturedAt(),
        d.getPayload() != null ? d.getPayload().toString() : null,
        d.getSkipDetail() != null ? d.getSkipDetail().toString() : null
    );
  }
}
