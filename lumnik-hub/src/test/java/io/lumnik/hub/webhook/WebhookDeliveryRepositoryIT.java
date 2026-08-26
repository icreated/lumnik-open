package io.lumnik.hub.webhook;

import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * recentFailedForEndpoint must report every non-success delivery. WebhookDeliveryService
 * treats anything outside 2xx as failed (succeeded=false), so a 3xx response is a failure
 * even though its status is < 400 — the view must key off succeeded, not the status range.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookDeliveryRepositoryIT {

    @Inject WebhookDeliveryRepository repo;
    @Inject EntityManager em;

    private Long endpointId;

    @BeforeEach
    @Transactional
    void seed() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'rfe.test.%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name = 'rfe-ep'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'rfe-ep', 'http://localhost:1/x', 'generic', 'NONE', '{rfe.test.*}', TRUE)
            """).executeUpdate();
        endpointId = ((Number) em.createNativeQuery(
            "SELECT id FROM core.webhook_endpoint WHERE name = 'rfe-ep'").getSingleResult()).longValue();

        insertWithStatus("rfe.test.ok", true, 200);
        insertWithStatus("rfe.test.redirect", false, 301); // failed per deliver(), but < 400
        insertWithStatus("rfe.test.server", false, 500);
        // transport failure: no HTTP status at all
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            VALUES (1, :ep, 'rfe.test.transport', '{}'::jsonb, NOW(), 1, FALSE)
            """).setParameter("ep", endpointId).executeUpdate();
    }

    private void insertWithStatus(String type, boolean succeeded, int status) {
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded, http_status)
            VALUES (1, :ep, :t, '{}'::jsonb, NOW(), 1, :ok, :st)
            """)
          .setParameter("ep", endpointId).setParameter("t", type)
          .setParameter("ok", succeeded).setParameter("st", status).executeUpdate();
    }

    @Test
    @Transactional
    void recentFailedReportsEveryNonSuccessIncluding3xx() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        Set<String> failed = repo.recentFailedForEndpoint(endpointId, 100).stream()
            .map(WebhookDelivery::getEventType)
            .filter(t -> t.startsWith("rfe.test."))
            .collect(Collectors.toSet());

        assertTrue(failed.contains("rfe.test.redirect"),
            "a 3xx delivery is failed (succeeded=false) and must appear in the failed view: " + failed);
        assertTrue(failed.contains("rfe.test.server"), "5xx must appear: " + failed);
        assertTrue(failed.contains("rfe.test.transport"), "transport failure (null status) must appear: " + failed);
        assertFalse(failed.contains("rfe.test.ok"), "a 2xx success must not appear in the failed view: " + failed);
    }
}
