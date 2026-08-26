package io.lumnik.common.eventing;

public interface DomainEventPublisher {
    void publish(Long tenantId, String eventType, Object payload);
}
