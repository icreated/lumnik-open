package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.domain.ConnectorEndpoint;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EndpointDto(
    UUID id,
    UUID connectorId,
    Long tenantId,
    String name,
    String method,
    String path,
    String schedule,
    boolean enabled,
    Map<String, Object> config,
    Instant lastRunAt,
    String lastRunStatus
) {
    public static EndpointDto from(ConnectorEndpoint ep) {
        return new EndpointDto(
            ep.getId(), ep.getConnectorId(), ep.getTenantId(),
            ep.getName(), ep.getMethod(), InlineCredentials.redactedUrl(ep.getPath()),   // #2003
            ep.getSchedule(), ep.isEnabled(), InlineCredentials.redacted(ep.getConfig()),   // #2003
            ep.getLastRunAt(), ep.getLastRunStatus());
    }
}
