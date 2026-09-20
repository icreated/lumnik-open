package io.lumnik.hub.webhook;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real WebhookDeliveryService.deliver is total (it returns a failure result, never throws),
 * so the only way to reach retryBatch's catch block is a JPA/infra throw. Here we mock deliver
 * to throw, proving a permanently-throwing delivery still counts its attempts and caps at 3
 * instead of being reprocessed every 30s forever.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookRetryThrowIT {

    @InjectMock WebhookDeliveryService deliveryService;
    @Inject WebhookConsumer consumer;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // The retry sweep claims globally (all tenants/types) — this test asserts a global deliver()
        // count, so remove ANY retryable row other tests may have left behind, not just ours.
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'thr.test.%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'thr-%'").executeUpdate();
    }

    @Transactional
    void seedEndpointAndRetryableDelivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'thr-ep', 'http://localhost:1/none', 'generic', 'NONE', '{thr.test.*}', TRUE)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'thr.test.boom', '{"k":"v"}'::jsonb, NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'thr-ep'
            """).executeUpdate();
    }

    @Transactional
    long countRetryable() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3 "
            + "AND event_type LIKE 'thr.test.%'").getSingleResult()).longValue();
    }

    @Test
    void aThrowingRetryStillCountsTheAttemptAndCapsAtThree() {
        when(deliveryService.deliver(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));
        seedEndpointAndRetryableDelivery();

        TenantContext.runAsSystem(consumer::retryBatch); // attempts 1 -> 2
        TenantContext.runAsSystem(consumer::retryBatch); // attempts 2 -> 3
        TenantContext.runAsSystem(consumer::retryBatch); // 3 not < 3: row excluded, no-op

        assertEquals(0, countRetryable(),
            "a row that throws on every retry must cap at attempts=3, not loop forever");
        verify(deliveryService, times(2)).deliver(any(), any(), any(), any());
    }

    @Transactional
    void seedEndpointAndStaleDelivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'thr-ep', 'http://localhost:1/none', 'generic', 'NONE', '{thr.test.*}', TRUE)
            """).executeUpdate();
        // attempt 1 reached HTTP and recorded a 500 + body + duration; the next retry will throw.
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded,
                 http_status, response_body, duration_ms)
            SELECT 1, e.id, 'thr.test.stale', '{"k":"v"}'::jsonb, NOW(), 1, FALSE, 500, 'prev body', 42
            FROM core.webhook_endpoint e WHERE e.name = 'thr-ep'
            """).executeUpdate();
    }

    @Transactional
    Object[] staleRow() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery(
            "SELECT attempts, http_status, response_body, duration_ms, error "
            + "FROM core.webhook_delivery WHERE event_type = 'thr.test.stale'").getSingleResult();
    }

    @Test
    void aThrowingRetryClearsStaleHttpFields() {
        when(deliveryService.deliver(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));
        seedEndpointAndStaleDelivery();

        TenantContext.runAsSystem(consumer::retryBatch); // throws before any HTTP response

        Object[] row = staleRow();
        assertEquals(2, ((Number) row[0]).intValue(), "attempts bumped");
        assertNull(row[1], "http_status must be cleared — no HTTP response happened this attempt");
        assertNull(row[2], "response_body must be cleared");
        assertNull(row[3], "duration_ms must be cleared");
        assertTrue(((String) row[4]).startsWith("retry threw"), "error reflects the throw: " + row[4]);
    }

    @Transactional
    void seedUnsubscribedEndpointWithStaleDelivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // endpoint event_types do NOT match the delivery's type -> retry hits the give-up branch.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'thr-unsub', 'http://localhost:1/none', 'generic', 'NONE', '{thr.other.*}', TRUE)
            """).executeUpdate();
        // attempt 1 recorded a 500 + body + duration before the endpoint was unsubscribed.
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded,
                 http_status, response_body, duration_ms)
            SELECT 1, e.id, 'thr.test.gone', '{"k":"v"}'::jsonb, NOW(), 1, FALSE, 500, 'prev body', 42
            FROM core.webhook_endpoint e WHERE e.name = 'thr-unsub'
            """).executeUpdate();
    }

    @Transactional
    Object[] goneRow() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery(
            "SELECT attempts, http_status, response_body, duration_ms, error, succeeded "
            + "FROM core.webhook_delivery WHERE event_type = 'thr.test.gone'").getSingleResult();
    }

    @Test
    void givingUpOnUnsubscribedEndpointClearsStaleHttpFields() {
        seedUnsubscribedEndpointWithStaleDelivery();

        TenantContext.runAsSystem(consumer::retryBatch); // give-up branch: no HTTP exchange

        Object[] row = goneRow();
        assertEquals(3, ((Number) row[0]).intValue(), "capped at 3 — endpoint unsubscribed");
        assertNull(row[1], "http_status must be cleared — no HTTP exchange for the give-up decision");
        assertNull(row[2], "response_body must be cleared");
        assertNull(row[3], "duration_ms must be cleared");
        assertTrue(((String) row[4]).contains("unsubscribed") || ((String) row[4]).contains("gone"),
            "error explains the give-up: " + row[4]);
        assertEquals(Boolean.FALSE, row[5], "succeeded stays false");
    }
}
