package io.lumnik.hub.api.connector.handler;

import java.util.UUID;

public record ApplyResult(
    String name,
    UUID connectorId,
    int endpointsApplied
) {}
