package io.lumnik.hub.api.dlq;

import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import java.time.Instant;
import java.util.UUID;

public record DlqAdminDto(
    UUID id,
    UUID connectorId,
    UUID runId,
    String skipReason,
    String errorMessage,
    int attempts,
    String resolution,
    Instant capturedAt,
    String payload,
    String skipDetail
) {
  public static DlqAdminDto from(ConnectorDeadLetter d) {
    return new DlqAdminDto(
        d.getId(), d.getConnectorId(), d.getRunId(),
        d.getSkipReason(), d.getErrorMessage(),
        d.getAttempts(), d.getResolution(),
        d.getCapturedAt(),
        d.getPayload() != null ? d.getPayload().toString() : null,
        d.getSkipDetail() != null ? d.getSkipDetail().toString() : null
    );
  }
}
