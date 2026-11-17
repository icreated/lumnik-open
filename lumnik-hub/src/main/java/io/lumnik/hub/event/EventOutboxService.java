package io.lumnik.hub.event;

import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Map;

/**
 * Inserts domain events into core.event_outbox within the current transaction.
 * The cursor consumers (WebhookConsumer, EmailConsumer) deliver them asynchronously.
 */
@ApplicationScoped
public class EventOutboxService {

    @Inject
    EntityManager em;

    /** Whether the event was written: {@code false} when there is no tenant to write it under. */
    @Transactional
    public boolean publish(String eventType, Map<String, Object> payload) {
        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return false;
        em.persist(EventOutboxRow.of(tenantId, eventType, payload));
        return true;
    }
}
