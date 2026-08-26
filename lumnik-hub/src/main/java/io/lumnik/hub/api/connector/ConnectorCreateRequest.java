package io.lumnik.hub.api.connector;

import java.util.Map;

public record ConnectorCreateRequest(
    Long tenantId,
    String name,
    String typeId,
    String schedule,
    boolean enabled,
    Map<String, Object> config
) {}
