package io.lumnik.hub.webhook;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class WebhookEndpointRepository implements PanacheRepository<WebhookEndpoint> {
    public List<WebhookEndpoint> findEnabledForEvent(Long tenantId, String eventType) {
        return list("tenantId = ?1 AND enabled = true AND archivedAt IS NULL AND deletedAt IS NULL",
                    tenantId)
            .stream().filter(e -> e.matches(eventType)).toList();
    }

    /**
     * The retry path's counterpart to {@link #findEnabledForEvent}: the same liveness rule, by id.
     *
     * <p>A plain {@code findById} filters nothing, and {@code softDelete()} stamps only
     * {@code deletedAt} — {@code enabled} stays true, so {@link WebhookEndpoint#matches} still
     * passed and a delivery already in flight kept being retried to an integration the customer
     * had deleted. Deletion has to stop the retries too, not just the first attempt.
     */
    public WebhookEndpoint findLiveById(Long id) {
        return find("id = ?1 AND archivedAt IS NULL AND deletedAt IS NULL", id).firstResult();
    }
}
