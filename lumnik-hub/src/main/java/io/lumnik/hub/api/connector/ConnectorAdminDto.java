package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConnectorAdminDto(
    UUID id,
    Long tenantId,
    String name,
    String typeId,
    String schedule,
    boolean enabled,
    Map<String, Object> config,
    List<String> tags,
    String status,
    Instant lastRunAt
) {
  public static ConnectorAdminDto from(Connector c) {
    return fromWithRun(c, null);
  }

  public static ConnectorAdminDto fromWithRun(Connector c, ConnectorRun lastRun) {
    String status = lastRun != null ? lastRun.getStatus() : null;
    Instant lastRunAt = lastRun != null ? lastRun.getStartedAt() : null;
    return new ConnectorAdminDto(
        c.getId(), c.getTenantId(), c.getName(), c.getTypeId(),
        c.getSchedule(), c.isEnabled(), c.getConfig(),
        c.getTags() != null ? c.getTags() : List.of(),
        status, lastRunAt);
  }
}
