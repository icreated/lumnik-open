package io.lumnik.hub.connector.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired in-process at connector-run finalization (alongside the outbox "connector.run.completed").
 * Consumed by ask's SchemaCardRefresher. Deliberately NOT an outbox payload — an in-process concern.
 */
public record ConnectorRunCompleted(UUID connectorId, long tenantId, UUID runId, Instant runStartedAt) {}
