package io.lumnik.hub.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.event.EntityFilters;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.BatchProcessor;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        TenantContext.runAsSystem(() -> sweeper.drain(CONSUMER_NAME, 100, this::batchProcessor));
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
                         String eventType, Long eventId, String payload) {}

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
                if (ep == null || !wants(ep, d.getEventType(), entityOf(d))) {
                    d.setAttempts(3); // endpoint gone, unsubscribed, or entity_filter no longer matches: stop retrying
                    markNoHttpResult(d, "endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped");
                    d.setClaimedUntil(null);
                    return new Claim(d.getId(), d.getTenantId(), null, null, null, null);
                }
                return new Claim(d.getId(), d.getTenantId(), ep, d.getEventType(), d.getEventId(),
                                 d.getPayload().toString());
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
            r = deliveryService.deliver(c.endpoint(), c.eventType(), c.eventId(), c.payload());
        } catch (RuntimeException e) {
            // Count the failed attempt so a row that throws every time still caps at 3
            // (claimNext: attempts < 3) instead of being reprocessed every 30s forever.
            // The kind, never the exception: a message or a stack can quote the target URL, which
            // is often the webhook's credential itself (#1162, #1166).
            LOG.errorf("retryBatch: delivery %d threw — %s — counting the attempt",
                       c.deliveryId(), OutboundFailure.describe(e));
            recordOutcome(c, d -> {
                d.setAttempts(d.getAttempts() + 1);
                markNoHttpResult(d, "retry threw: " + OutboundFailure.describe(e));
            });
            return;
        }
        if (!r.ok()) {
            LOG.warnf("Webhook delivery failed: delivery %d event %s — %s (retry sweep will pick it up)",
                      c.deliveryId(), c.eventId(), r.error());
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

    /**
     * Whether a live endpoint subscribes to this event — one rule for the first delivery and the
     * retry alike. entity_filter narrows only events that carry an entity; entity-less events
     * reach every endpoint subscribed to their type.
     */
    private static boolean wants(WebhookEndpoint ep, String eventType, String entity) {
        return ep.matches(eventType) && EntityFilters.passes(ep.getEntityFilter(), entity);
    }

    /** Reads the entity from a stored delivery payload (data events carry {entity, ...}); null when absent. */
    private static String entityOf(WebhookDelivery d) {
        JsonNode p = d.getPayload();
        if (p == null) return null;
        JsonNode e = p.get("entity");
        return (e == null || e.isNull()) ? null : e.asText();
    }

    /**
     * The processor of one outbox batch. A tenant's live endpoints are read at its first event in
     * the batch and reused for the rest of it, never for the next one: an endpoint created, changed
     * or deleted is seen from the next batch on, so at most the rest of the current batch is late.
     *
     * <p>The batch only RECORDS (#944): one pending {@code webhook_delivery} row per (event,
     * endpoint), leased. Each event commits its rows in its own transaction (#943) and the batch
     * commits the cursor after them, so a failure of the batch's own commit can leave rows without
     * the cursor: the replay records them again, and the first rows are delivered too, once their
     * lease expires. {@link BatchProcessor#afterCommit} then POSTs each with nothing held and
     * records its outcome in a short transaction — the retry path's claim / deliver / record. A
     * crash between the two loses nothing: the leased rows are there, and the retry sweep delivers
     * them once the lease expires.
     */
    public BatchProcessor batchProcessor() {
        return new BatchProcessor() {
            private final Map<Long, List<WebhookEndpoint>> liveByTenant = new HashMap<>();
            private final List<Pending> pending = new ArrayList<>();

            @Override public void accept(EventOutboxRow row) { record(row, liveByTenant, pending); }

            @Override public void afterCommit() { deliverPending(pending); }
        };
    }

    /** A first delivery recorded by a batch, and the lease the batch gave it. */
    private record Pending(Claim claim, OffsetDateTime leasedUntil) {}

    private void record(EventOutboxRow row, Map<Long, List<WebhookEndpoint>> liveByTenant, List<Pending> pending) {
        List<WebhookEndpoint> targets =
            liveByTenant.computeIfAbsent(row.getTenantId(), endpointRepo::findEnabled).stream()
                .filter(ep -> wants(ep, row.getEventType(), row.getEntity()))
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

        // Microseconds: the precision PostgreSQL stores, so extendClaim can compare it exactly.
        OffsetDateTime leasedUntil =
            OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(CLAIM_LEASE_SECONDS).truncatedTo(ChronoUnit.MICROS);
        for (WebhookEndpoint ep : targets) {
            WebhookDelivery d = new WebhookDelivery();
            d.setTenantId(row.getTenantId());
            d.setEndpointId(ep.getId());
            d.setEventId(row.getId());
            d.setEventType(row.getEventType());
            d.setPayload(payloadNode);
            d.setAttempts(0);          // pending: never attempted
            d.setClaimedUntil(leasedUntil);
            deliveryRepo.persist(d);
            pending.add(new Pending(
                new Claim(d.getId(), row.getTenantId(), ep, row.getEventType(), row.getId(), body), leasedUntil));
        }
    }

    /**
     * Delivers what the committed batch recorded, with no transaction open. A row whose event
     * rolled back (a failed event costs only itself, #943) no longer exists, and one the retry
     * sweep took over is no longer ours: both fail {@code extendClaim} and are skipped.
     */
    private void deliverPending(List<Pending> pending) {
        for (Pending p : pending) {
            try {
                if (renewLease(p)) deliverClaimed(p.claim());
                else LOG.debugf("First delivery %d no longer ours (event rolled back, or the retry sweep took it) — skipped",
                                p.claim().deliveryId());
            } catch (RuntimeException e) {
                // Left leased and unattempted: the retry sweep delivers it when the lease expires.
                LOG.errorf(e, "First delivery %d failed before its outcome was recorded", p.claim().deliveryId());
            }
        }
    }

    private boolean renewLease(Pending p) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery(SET_SYSTEM_TENANT).executeUpdate();
            return deliveryRepo.extendClaim(p.claim().deliveryId(), p.leasedUntil(), CLAIM_LEASE_SECONDS);
        });
    }
}
