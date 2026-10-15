package io.lumnik.hub.webhook;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.BatchProcessor;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #944: the FIRST delivery used to POST to the customer's endpoint inside the outbox batch's
 * transaction, pinning a pooled connection and the cursor's lock for up to 15 s per endpoint, and
 * rolling cursor and rows back (re-POSTing) if the batch outlasted the transaction timeout.
 *
 * <p>Now the batch transaction only records a pending delivery per (event, endpoint) and moves the
 * cursor; the POST happens after it commits, with nothing held, and its outcome is written in a
 * short transaction of its own — the retry path's claim / deliver / record.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookFirstDeliveryNoTxIT {

    private static final String CONSUMER = "ntx-first";

    @InjectMock WebhookDeliveryService deliveryService;
    @Inject OutboxSweeper sweeper;
    @Inject WebhookConsumer consumer;
    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject EntityManager em;

    /** Fail-closed: starts EMPTY, so an assertion on its contents cannot pass without a real call. */
    private final List<Boolean> txActiveAtDelivery = new CopyOnWriteArrayList<>();

    @BeforeEach
    @Transactional
    void clean() {
        txActiveAtDelivery.clear();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'ntx1.test.%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'ntx1-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = :c").setParameter("c", CONSUMER).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'ntx1-ep', 'http://localhost:1/none', 'generic', 'NONE', '{ntx1.test.*}', TRUE)
            """).executeUpdate();
    }

    @Transactional
    void seedEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(1L, type, Map.of("k", "v")));
    }

    private void stubDelivery() {
        when(deliveryService.deliver(any(), any(), any(), any())).thenAnswer(inv -> {
            txActiveAtDelivery.add(QuarkusTransaction.isActive());
            return WebhookDeliveryService.DeliveryResult.success(200, "ok", 1);
        });
    }

    /** attempts, succeeded, claim released — of the one delivery of the test's event type. */
    @Transactional
    Object[] deliveryOf(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery(
            "SELECT attempts, succeeded, claimed_until IS NULL FROM core.webhook_delivery WHERE event_type = :t")
            .setParameter("t", type).getSingleResult();
    }

    @Transactional
    long cursor() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery("SELECT last_event_id FROM core.event_consumer WHERE consumer_name = :c")
            .setParameter("c", CONSUMER).getSingleResult()).longValue();
    }

    @Transactional
    void expireClaims() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.webhook_delivery SET claimed_until = now() - INTERVAL '1 second' WHERE event_type LIKE 'ntx1.test.%'")
          .executeUpdate();
    }

    @Test
    void theFirstDeliveryRunsWithNoTransactionHeldAndIsRecorded() {
        stubDelivery();
        seedEvent("ntx1.test.first");

        TenantContext.runAsSystem(() -> sweeper.drain(CONSUMER, 100, consumer::batchProcessor));

        assertEquals(List.of(Boolean.FALSE), txActiveAtDelivery,
            "deliver() was never called, or ran inside a transaction");
        Object[] row = deliveryOf("ntx1.test.first");
        assertEquals(1, ((Number) row[0]).intValue(), "attempts");
        assertEquals(Boolean.TRUE, row[1], "succeeded");
        assertEquals(Boolean.TRUE, row[2], "claim released");
    }

    /**
     * The chosen crash semantics (at-least-once): the batch commits the cursor TOGETHER with a
     * pending row per delivery. A crash before the POST loses nothing — the row is there, leased,
     * and the retry sweep delivers it once the lease expires, never before.
     */
    @Test
    void aCrashAfterTheBatchCommitLosesNothingTheRetrySweepDeliversItWhenTheLeaseExpires() {
        stubDelivery();
        seedEvent("ntx1.test.crash");

        // The batch alone: afterCommit() is what a crash prevents from running.
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER, 100, consumer.batchProcessor()));

        verify(deliveryService, never()).deliver(any(), any(), any(), any());
        Object[] pending = deliveryOf("ntx1.test.crash");
        assertEquals(0, ((Number) pending[0]).intValue(), "pending: never attempted");
        assertEquals(Boolean.FALSE, pending[1]);
        assertEquals(Boolean.FALSE, pending[2], "leased");
        assertTrue(cursor() > 0, "the cursor moved past the event in the same commit");

        TenantContext.runAsSystem(consumer::retryBatch);
        assertTrue(txActiveAtDelivery.isEmpty(), "the lease still holds: the retry sweep must not take it yet");

        expireClaims();
        TenantContext.runAsSystem(consumer::retryBatch);

        assertEquals(List.of(Boolean.FALSE), txActiveAtDelivery);
        Object[] done = deliveryOf("ntx1.test.crash");
        assertEquals(1, ((Number) done[0]).intValue());
        assertEquals(Boolean.TRUE, done[1]);
        assertEquals(Boolean.TRUE, done[2]);
    }

    /**
     * A lease the retry sweep took over while the batch was still delivering must not be POSTed
     * twice: the row is still unattempted (the other owner is mid-flight), only its lease differs.
     */
    @Test
    void afterCommitSkipsADeliveryWhoseLeaseWasTakenOver() {
        stubDelivery();
        seedEvent("ntx1.test.taken");
        BatchProcessor batch = consumer.batchProcessor();
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER, 100, batch));

        takeOverLease(); // another sweep claimed it: a different lease, still no outcome

        TenantContext.runAsSystem(batch::afterCommit);

        verify(deliveryService, never()).deliver(any(), any(), any(), any());
    }

    @Transactional
    void takeOverLease() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.webhook_delivery SET claimed_until = now() + INTERVAL '30 seconds' WHERE event_type = 'ntx1.test.taken'")
          .executeUpdate();
    }

    /** A pending row is not an output yet: the admin views list attempts, not intentions. */
    @Test
    void aPendingDeliveryIsNotListedByTheAdminViews() {
        seedEvent("ntx1.test.hidden");
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER, 100, consumer.batchProcessor()));
        assertFalse(deliveryOf("ntx1.test.hidden")[1].equals(Boolean.TRUE));

        List<String> listed = QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '1'").executeUpdate();
            Long endpointId = ((Number) em.createNativeQuery("SELECT id FROM core.webhook_endpoint WHERE name = 'ntx1-ep'")
                .getSingleResult()).longValue();
            List<String> types = new ArrayList<>();
            deliveryRepo.recentForTenant(1L, 500).forEach(d -> types.add(d.getEventType()));
            deliveryRepo.recentForEndpoint(endpointId, 500).forEach(d -> types.add(d.getEventType()));
            deliveryRepo.recentFailedForEndpoint(endpointId, 500).forEach(d -> types.add(d.getEventType()));
            return types;
        });

        assertFalse(listed.contains("ntx1.test.hidden"), "listed: " + listed);
    }
}
