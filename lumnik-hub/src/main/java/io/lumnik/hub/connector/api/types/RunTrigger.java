package io.lumnik.hub.connector.api.types;

import java.util.UUID;

public sealed interface RunTrigger {
  record Schedule(String cronExpression)             implements RunTrigger {}
  record Manual(Long userId)                         implements RunTrigger {}
  record Event(String eventType, UUID eventId)       implements RunTrigger {}
  record Replay(UUID sourceRunId, Long userId)       implements RunTrigger {}
}
