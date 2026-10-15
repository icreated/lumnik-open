package io.lumnik.hub.notify;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class NotificationRuleRepository implements PanacheRepository<NotificationRule> {
    /** Every enabled rule of one tenant — the consumer matches event type and entity against them. */
    public List<NotificationRule> findEnabled(Long tenantId) {
        return list("tenantId = ?1 AND enabled = true", tenantId);
    }
}
