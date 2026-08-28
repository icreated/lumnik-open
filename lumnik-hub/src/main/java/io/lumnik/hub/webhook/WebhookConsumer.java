package io.lumnik.hub.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EntityFilters;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Outbox consumer "webhook": delivers each event to the tenant's subscribed
 * endpoints. Per-delivery outcome (succeeded/attempts) lives in webhook_delivery;
 * failed deliveries are redelivered by the retry sweep in Task 3.
 */
@ApplicationScoped
public class WebhookConsumer {

    /**
     * The cross-tenant sentinel this consumer runs its sweeps under. RLS reads
     * app.current_tenant; '0' is the system value, and PostgreSQL accepts no bind
     * placeholder in SET LOCAL — so the statement is a literal by necessity, spelled once
     * so the three sweeps cannot drift apart.
     */
    private static final String SET_SYSTEM_TENANT = "SET LOCAL app.current_tenant = '0'";

    static final String CONSUMER_NAME = "webhook";
    private static final Logger LOG = Logger.getLogger(WebhookConsumer.class);

    /** Deliveries retried per tick, unchanged: the sweep now claims them one at a time. */
    private static final int RETRY_BATCH_SIZE = 100;

    /**
     * Claim lease. One delivery costs at most 15s of HTTP (WebhookDeliveryService: 5s connect +
     * 10s request), so 60s covers a worst-case delivery four times over. It is also the maximum
     * time a delivery stays stranded if this process dies mid-flight: the claim then expires and
     * the next sweep — here or on another instance — picks the row up again. Deliberately not
     * configurable: it is derived from the HTTP budget, which is itself fixed.
     */
    private static final int CLAIM_LEASE_SECONDS = 60;

    @Inject OutboxSweeper sweeper;
    @Inject WebhookEndpointRepository endpointRepo;
    @Inject WebhookDeliveryService deliveryService;
    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject ObjectMapper mapper;
    @Inject EntityManager em;

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    void tick() {
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER_NAME, 100, this::process));
    }

    @Scheduled(every = "30s", concurrentExecution = SKIP)
    void retryTick() {
        TenantContext.runAsSystem(this::retryBatch);
    }

    /**
     * Per-delivery retry: the failed webhook_delivery row is the retry unit (not the event),
     * so endpoints that already succeeded are never redelivered. Caps at 3 attempts.
     *
     * <p>Deliberately NOT {@code @Transactional}. Each row is claimed in a short transaction
     * (a {@code claimed_until} lease that outlives it — see {@link WebhookDeliveryRepository
     * #claimNext}), delivered to the CUSTOMER's endpoint with no transaction and no pooled
     * connection held, and its outcome written in a second short transaction. Claiming per row
     * rather than per batch is what makes the lease sound: deliveries run sequentially, so the
     * last row of a batch claimed up front would be delivered ~25 minutes after its claim, long
     * past any sane lease.
     */
    void retryBatch() {
        long afterId = 0;
        for (int i = 0; i < RETRY_BATCH_SIZE; i++) {
            Claim claim = claimNext(afterId);
            if (claim == null) return;             // nothing left to retry
            afterId = claim.deliveryId();          // one attempt per row per sweep, as before
            if (claim.endpoint() != null) deliverClaimed(claim);
        }
    }

    /** A claimed delivery, detached from its claim transaction. A null endpoint = already given up on. */
    private record Claim(long deliveryId, long tenantId, WebhookEndpoint endpoint,
                         String eventType, String payload) {}

    /**
     * Claims the next retryable delivery and resolves its endpoint, in one short transaction.
     * The give-up decision (endpoint gone/unsubscribed/filtered out) performs no HTTP, so it is
     * settled and written here rather than costing a second round trip.
     */
    private Claim claimNext(long afterId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // System sentinel: webhook_delivery policy gained the '0' branch in V24. SET LOCAL is
            // scoped to a transaction, so every short transaction below sets the GUC for itself.
            em.createNativeQuery(SET_SYSTEM_TENANT).executeUpdate();
            WebhookDelivery d = deliveryRepo.claimNext(afterId, CLAIM_LEASE_SECONDS);
            if (d == null) return null;
            try {
                em.createNativeQuery("SET LOCAL app.current_tenant = '" + d.getTenantId() + "'")
                  .executeUpdate();
                CurrentTenantHolder.set(d.getTenantId());
                WebhookEndpoint ep = endpointRepo.findLiveById(d.getEndpointId());
                if (ep == null || !ep.matches(d.getEventType()) || !entityFilterPasses(ep, entityOf(d))) {
                    d.setAttempts(3); // endpoint gone, unsubscribed, or entity_filter no longer matches: stop retrying
                    markNoHttpResult(d, "endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped");
                    d.setClaimedUntil(null);
                    return new Claim(d.getId(), d.getTenantId(), null, null, null);
                }
                return new Claim(d.getId(), d.getTenantId(), ep, d.getEventType(), d.getPayload().toString());
            } finally {
                CurrentTenantHolder.clear();
                em.createNativeQuery(SET_SYSTEM_TENANT).executeUpdate();
            }
        });
    }

    /** Delivers a claimed row with NO transaction open, then records the outcome in a short one. */
    private void deliverClaimed(Claim c) {
        WebhookDeliveryService.DeliveryResult r;
        try {
            r = deliveryService.deliver(c.endpoint(), c.eventType(), c.payload());
        } catch (RuntimeException e) {
            // Count the failed attempt so a row that throws every time still caps at 3
            // (claimNext: attempts < 3) instead of being reprocessed every 30s forever.
            LOG.errorf(e, "retryBatch: delivery %d threw — counting the attempt", c.deliveryId());
            recordOutcome(c, d -> {
                d.setAttempts(d.getAttempts() + 1);
                markNoHttpResult(d, "retry threw: " + e.getMessage());
            });
            return;
        }
        recordOutcome(c, d -> {
            d.setAttempts(d.getAttempts() + 1);
            d.setAttemptedAt(OffsetDateTime.now(ZoneOffset.UTC));
            d.setSucceeded(r.ok());
            d.setHttpStatus(r.httpStatus());
            d.setResponseBody(r.responseBody());
            d.setError(r.error());
            d.setDurationMs(r.durationMs());
        });
    }

    /** Writes one delivery's outcome and releases its claim, in its own short transaction. */
    private void recordOutcome(Claim c, Consumer<WebhookDelivery> outcome) {
        QuarkusTransaction.requiringNew().run(() -> {
            try {
                // This transaction touches webhook_delivery (RLS-enforced) and SET LOCAL died with
                // the claim transaction, so bind the row's tenant again here.
                em.createNativeQuery("SET LOCAL app.current_tenant = '" + c.tenantId() + "'")
                  .executeUpdate();
                CurrentTenantHolder.set(c.tenantId());
                WebhookDelivery d = deliveryRepo.findById(c.deliveryId());
                if (d == null) return; // purged under us — nothing left to record
                outcome.accept(d);
                d.setClaimedUntil(null); // claim released: retryable again unless it just capped
            } finally {
                CurrentTenantHolder.clear();
                em.createNativeQuery(SET_SYSTEM_TENANT).executeUpdate();
            }
        });
    }

    /**
     * Record a retry attempt that performed no HTTP exchange (endpoint gone/unsubscribed, or a
     * throw before deliver): stamp the failure + reason and clear the prior attempt's HTTP fields
     * so the row's "last attempt" state isn't misleading in the admin view. The caller sets attempts.
     */
    private static void markNoHttpResult(WebhookDelivery d, String error) {
        d.setAttemptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        d.setSucceeded(false);
        d.setHttpStatus(null);
        d.setResponseBody(null);
        d.setDurationMs(null);
        d.setError(error);
    }

    /** entity_filter narrows only events that carry an entity; entity-less events reach every subscribed endpoint. */
    private static boolean entityFilterPasses(WebhookEndpoint ep, String entity) {
        return EntityFilters.passes(ep.getEntityFilter(), entity);
    }

    /** Reads the entity from a stored delivery payload (data events carry {entity, ...}); null when absent. */
    private static String entityOf(WebhookDelivery d) {
        JsonNode p = d.getPayload();
        if (p == null) return null;
        JsonNode e = p.get("entity");
        return (e == null || e.isNull()) ? null : e.asText();
    }

    public void process(EventOutboxRow row) {
        List<WebhookEndpoint> targets =
            endpointRepo.findEnabledForEvent(row.getTenantId(), row.getEventType()).stream()
                .filter(ep -> entityFilterPasses(ep, row.getEntity()))
                .toList();
        if (targets.isEmpty()) return;

        String body;
        try {
            body = mapper.writeValueAsString(row.getPayload());
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialize payload for event %d", row.getId());
            return;
        }
        JsonNode payloadNode = mapper.valueToTree(row.getPayload());

        for (WebhookEndpoint ep : targets) {
            var r = deliveryService.deliver(ep, row.getEventType(), body);
            persistAttempt(row, ep.getId(), payloadNode, r);
            if (!r.ok()) {
                LOG.warnf("Webhook delivery failed: event %d endpoint %s — %s (retry sweep will pick it up)",
                          row.getId(), ep.getName(), r.error());
            }
        }
    }

    private void persistAttempt(EventOutboxRow row, Long endpointId,
                                JsonNode payload, WebhookDeliveryService.DeliveryResult r) {
        WebhookDelivery d = new WebhookDelivery();
        d.setTenantId(row.getTenantId());
        d.setEndpointId(endpointId);
        d.setEventId(row.getId());
        d.setEventType(row.getEventType());
        d.setPayload(payload);
        d.setHttpStatus(r.httpStatus());
        d.setResponseBody(r.responseBody());
        d.setError(r.error());
        d.setDurationMs(r.durationMs());
        d.setAttempts(1);
        d.setSucceeded(r.ok());
        deliveryRepo.persist(d);
    }
}
