package io.lumnik.hub.webhook;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class WebhookDeliveryRepository implements PanacheRepository<WebhookDelivery> {


    /** The delivery-ordering column every query here sorts on. */
    private static final String ATTEMPTED_AT = "attemptedAt";

    public List<WebhookDelivery> recentForEndpoint(Long endpointId, int limit) {
        return find("endpointId = ?1", Sort.descending(ATTEMPTED_AT), endpointId)
            .page(0, Math.min(limit, 500)).list();
    }

    public List<WebhookDelivery> recentFailedForEndpoint(Long endpointId, int limit) {
        // Key off succeeded (authoritative since V24): deliver() treats anything outside 2xx
        // as failed, so a 3xx — status < 400 — is still a failure and must appear here.
        return find("endpointId = ?1 AND succeeded = false",
            Sort.descending(ATTEMPTED_AT), endpointId)
            .page(0, Math.min(limit, 500)).list();
    }

    /** Every integration's deliveries for one tenant, FAILURES FIRST then newest — the order is
     *  the server's promise to the :outputs screen, which renders it and never re-derives it. */
    public List<WebhookDelivery> recentForTenant(long tenantId, int limit) {
        return find("tenantId = ?1",
                Sort.ascending("succeeded").and(ATTEMPTED_AT, Sort.Direction.Descending), tenantId)
            .page(0, Math.min(limit, 500)).list();
    }

    /**
     * Claims the next retryable delivery with an id above {@code afterId}, leasing it for
     * {@code leaseSeconds}; returns it, or {@code null} when nothing is eligible.
     *
     * <p>Eligibility is the retry predicate ({@code succeeded = FALSE AND attempts < 3}) plus
     * "not currently claimed" ({@code claimed_until IS NULL OR claimed_until < now()}). An
     * EXPIRED claim is deliberately reclaimable: a sweep that dies mid-delivery must not strand
     * its row forever.
     *
     * <p>Cross-instance protection: the UPDATE's subselect takes the row under FOR UPDATE SKIP
     * LOCKED, so two sweeps racing in the same instant never claim the same row; and because the
     * claim is a COLUMN rather than a lock, it keeps holding after this transaction commits —
     * which is what lets {@code WebhookConsumer.retryBatch} deliver with no transaction and no
     * pooled connection held. {@code @Scheduled(SKIP)} is per-JVM, so this is the only thing
     * stopping a customer endpoint receiving the same POST twice on a multi-replica deployment.
     *
     * <p>{@code afterId} is the caller's per-batch cursor: the outcome write releases the claim,
     * so without it a row that failed again would be immediately re-claimable and one sweep could
     * burn all three of its attempts. One attempt per row per sweep, as before.
     */
    public WebhookDelivery claimNext(long afterId, int leaseSeconds) {
        List<?> claimed = getEntityManager().createNativeQuery("""
            UPDATE core.webhook_delivery
               SET claimed_until = now() + (:lease * INTERVAL '1 second')
             WHERE id = (
                 SELECT id FROM core.webhook_delivery
                 WHERE id > :after
                   AND succeeded = FALSE AND attempts < 3
                   AND (claimed_until IS NULL OR claimed_until < now())
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
             )
            RETURNING id
            """)
            .setParameter("lease", leaseSeconds)
            .setParameter("after", afterId)
            .getResultList();
        return claimed.isEmpty() ? null : findById(((Number) claimed.get(0)).longValue());
    }
}
