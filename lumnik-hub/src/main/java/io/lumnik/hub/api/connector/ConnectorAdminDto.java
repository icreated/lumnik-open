package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A connector as the platform read doors serve it.
 *
 * <p>{@code window} rides beside {@code schedule} because the two are one decision — when this
 * connector may run — and they are stored the same way, each on its own column, which is what the
 * scheduler selects and fences on. It was missing here, so {@code lm connector list} showed a
 * SCHED column and no fence, and the flat {@code get -> edit -> apply} loop this record defines
 * dropped a declared window on the way back (#499).
 */
public record ConnectorAdminDto(
    UUID id,
    Long tenantId,
    String name,
    String typeId,
    String schedule,
    String window,
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
        c.getSchedule(), c.getRunWindow(), c.isEnabled(), c.getConfig(),
        c.getTags() != null ? c.getTags() : List.of(),
        status, lastRunAt);
  }
}
