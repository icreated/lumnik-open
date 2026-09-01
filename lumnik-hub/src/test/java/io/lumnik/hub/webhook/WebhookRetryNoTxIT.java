package io.lumnik.hub.webhook;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The retry sweep POSTs to a CUSTOMER's endpoint: 5s connect + 10s request (WebhookDeliveryService)
 * per delivery, up to 100 deliveries per tick. If that HTTP call happens inside a JTA transaction,
 * one hung endpoint pins a pooled connection for the whole batch. This proves the transaction is
 * released before the call — the row is claimed in a short transaction, delivered with nothing
 * held, and its outcome written in a second short transaction.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookRetryNoTxIT {

    @InjectMock WebhookDeliveryService deliveryService;
    @Inject WebhookConsumer consumer;
    @Inject EntityManager em;

    /** Fail-closed: starts EMPTY, so an assertion on its contents cannot pass without a real call. */
    private final List<Boolean> txActiveAtDelivery = new CopyOnWriteArrayList<>();

    @BeforeEach
    @Transactional
    void clean() {
        txActiveAtDelivery.clear();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // The retry sweep is global (all tenants/types): this test asserts on the exact set of
        // deliver() calls, so remove ANY retryable row other tests may have left behind.
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'ntx.test.%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'ntx-%'").executeUpdate();
    }

    @Transactional
    void seedEndpointAndRetryableDelivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'ntx-ep', 'http://localhost:1/none', 'generic', 'NONE', '{ntx.test.*}', TRUE)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'ntx.test.hang', '{"k":"v"}'::jsonb, NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'ntx-ep'
            """).executeUpdate();
    }

    @Test
    void deliverRunsWithNoTransactionHeld() {
        when(deliveryService.deliver(any(), any(), any())).thenAnswer(inv -> {
            txActiveAtDelivery.add(QuarkusTransaction.isActive());
            return WebhookDeliveryService.DeliveryResult.failure("stubbed", 0, 1);
        });
        seedEndpointAndRetryableDelivery();

        TenantContext.runAsSystem(consumer::retryBatch);

        assertFalse(txActiveAtDelivery.isEmpty(),
            "deliver() was never called — this test proves nothing until the sweep reaches it");
        assertEquals(List.of(Boolean.FALSE), txActiveAtDelivery,
            "the retry sweep must not hold a JTA transaction (and its pooled connection) across the outbound HTTP call");
    }
}
