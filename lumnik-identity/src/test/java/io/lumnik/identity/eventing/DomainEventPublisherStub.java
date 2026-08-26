package io.lumnik.identity.eventing;

import io.lumnik.common.eventing.DomainEventPublisher;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * No-op stub that satisfies CDI wiring in identity module tests,
 * where the real OutboxPublisher (from lumnik-hub) is absent.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class DomainEventPublisherStub implements DomainEventPublisher {
    @Override
    public void publish(Long tenantId, String eventType, Object payload) {
        // no-op in tests
    }
}
