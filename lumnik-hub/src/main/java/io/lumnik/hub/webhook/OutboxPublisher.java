package io.lumnik.hub.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.eventing.DomainEventPublisher;
import io.lumnik.hub.event.EventOutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Map;

@ApplicationScoped
public class OutboxPublisher implements DomainEventPublisher {

    @Inject EntityManager em;
    @Inject ObjectMapper mapper;

    @Transactional(Transactional.TxType.MANDATORY)
    public void publish(Long tenantId, String eventType, Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = mapper.convertValue(payload, Map.class);
        EventOutboxRow row = EventOutboxRow.of(tenantId, eventType, payloadMap);
        em.persist(row);
    }
}
