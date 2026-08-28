package io.lumnik.hub.webhook;

import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting an integration must stop deliveries already in flight to it.
 *
 * <p>The FIRST delivery asks {@code WebhookEndpointRepository.findEnabledForEvent}, which filters
 * {@code archivedAt IS NULL AND deletedAt IS NULL}. The RETRY asked {@code findById}, which filters
 * nothing — and {@code softDelete()} stamps only {@code deletedAt}, leaving {@code enabled = true},
 * so {@code WebhookEndpoint.matches} (which reads only {@code enabled} and the event types) still
 * passed and the give-up branch was skipped. A customer who deleted an integration kept receiving
 * from it for up to two more attempts.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookRetryDeletedEndpointIT {

    @Inject WebhookConsumer consumer;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // retryBatch claims globally — clear any retryable row another test left behind.
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'del.test.%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name = 'del-ep'").executeUpdate();
    }

    /** An enabled, matching endpoint with a delivery mid-flight — then the integration is deleted. */
    @Transactional
    void seedDeletedEndpointWithInFlightDelivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // The URL is deliberately unreachable: if the retry is attempted at all, the failure it
        // records is a connection error — visibly different from the give-up this test wants.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'del-ep', 'http://localhost:1/x', 'generic', 'NONE', '{del.test.*}', TRUE)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'del.test.one', '{}'::jsonb, NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'del-ep'
            """).executeUpdate();
        // softDelete() stamps deletedAt only — enabled stays TRUE, exactly as the entity does it.
        em.createNativeQuery("UPDATE core.webhook_endpoint SET deleted_at = NOW() WHERE name = 'del-ep'")
          .executeUpdate();
    }

    /**
     * The fourth send-capable door. `POST …/integrations/{id}/test` calls
     * {@code deliveryService.deliver(...)} — a real POST — and resolved its endpoint with a plain
     * {@code findById}. "Nothing more, by any door" has to include the one an operator reaches for
     * when checking whether the integration still works.
     */
    @Test
    void aDeletedIntegrationCannotBeTestFired() {
        seedDeletedEndpointWithInFlightDelivery();
        Long id = ((Number) em.createNativeQuery(
            "SELECT id FROM core.webhook_endpoint WHERE name = 'del-ep'").getSingleResult()).longValue();

        RestAssured.given()
            .header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .contentType("application/json").body("{\"eventType\":\"org.created\"}")
            .when().post("/api/tenants/1/integrations/" + id + "/test")
            .then().statusCode(404);
    }

    @Test
    void aDeletedIntegrationStopsReceivingItsInFlightRetries() {
        seedDeletedEndpointWithInFlightDelivery();

        consumer.retryBatch();

        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT attempts, error, http_status FROM core.webhook_delivery
            WHERE event_type = 'del.test.one'
            """).getSingleResult();
        int attempts = ((Number) row[0]).intValue();
        String error = (String) row[1];
        Object httpStatus = row[2];

        assertEquals(3, attempts,
            () -> "the retry must give up on a deleted integration, not spend another attempt "
                + "(error was: " + error + ")");
        assertTrue(error != null && error.contains("endpoint gone"),
            () -> "expected the give-up marker, got: " + error);
        assertEquals(null, httpStatus,
            "giving up performs no HTTP, so no status is recorded");
    }
}
