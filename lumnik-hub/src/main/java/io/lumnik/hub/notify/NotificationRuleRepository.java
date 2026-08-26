package io.lumnik.hub.notify;

import io.lumnik.hub.event.EntityFilters;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class NotificationRuleRepository implements PanacheRepository<NotificationRule> {
    /** entity narrows rules with an entity_filter; entity-less events reach every matching rule. */
    public List<NotificationRule> findMatching(Long tenantId, String eventType, String entity) {
        return list("tenantId = ?1 AND enabled = true", tenantId)
            .stream()
            .filter(r -> r.matches(eventType) && EntityFilters.passes(r.getEntityFilter(), entity))
            .toList();
    }
}
