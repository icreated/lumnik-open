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
     * {@link #findLiveById}'s tenant-scoped twin, for the doors that take the id from the request:
     * live AND ours, in one predicate. A bare {@code findById} cannot carry the tenant — Hibernate
     * never filters by-id lookups, and RLS is the only thing behind it — so the doors that update,
     * delete, replay through or test an endpoint by a path id resolve it through a predicate here
     * or through {@code Require.owned}, never through {@code findById}.
     */
    public WebhookEndpoint findLiveForTenant(Long id, Long tenantId) {
        return find("id = ?1 AND tenantId = ?2 AND archivedAt IS NULL AND deletedAt IS NULL",
                    id, tenantId).firstResult();
    }

    /** Whether this tenant has an endpoint by this id — 0 or 1, without hydrating the row. */
    public long countForTenant(Long id, Long tenantId) {
        return count("id = ?1 AND tenantId = ?2", id, tenantId);
    }

    /**
     * The live endpoint holding this name for a tenant, or null — the create and rename door's
     * pre-check.
     *
     * <p>The predicate is {@code deletedAt IS NULL} and NOT the {@code archivedAt IS NULL} the
     * readers above add, because this one does not answer "can it be used"; it answers "is the name
     * taken", and that question belongs to uq_webhook_endpoint_name (V46), whose predicate is
     * exactly this. The two must agree or the door refuses a set the index does not, and vice
     * versa. (No door archives a WebhookEndpoint today; V46 records why the predicate must not grow
     * to archived_at if one ever does.)
     */
    public WebhookEndpoint findLiveByName(Long tenantId, String name) {
        return find("tenantId = ?1 AND name = ?2 AND deletedAt IS NULL", tenantId, name).firstResult();
    }

    /** Every live endpoint of one tenant, newest rule first — the list door's whole query. */
    public List<WebhookEndpoint> listLiveForTenant(Long tenantId) {
        return list("tenantId = ?1 AND archivedAt IS NULL AND deletedAt IS NULL ORDER BY name",
                    tenantId);
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
