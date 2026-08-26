package io.lumnik.hub.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookConsumerIT {

    static WireMockServer wm;

    @Inject OutboxSweeper sweeper;
    @Inject WebhookConsumer consumer;
    @Inject EntityManager em;

    @BeforeAll
    static void startWm() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWm() { wm.stop(); }

    @BeforeEach
    @Transactional
    void clean() {
        wm.resetAll();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'whc.test.%'").executeUpdate();
        // Empty the whole log so the freshly-seeded whc.test.* rows fall within the single
        // manual sweep's batch window (id > cursor LIMIT n): this consumer's cursor is deleted
        // below, and prior ITs leave many low-id event_outbox rows that would otherwise fill
        // the first 100-row batch before our events. Same accepted pattern as EventSpineIT.
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'whc-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'webhook'").executeUpdate();
    }

    @Transactional
    void seedEndpoint(String name, String path) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // auth_mode NONE: no secret needed, the HTTP POST fires unconditionally (the V5 'secret'
        // plaintext column was dropped; HMAC mode requires secret_encrypted which we don't seed).
        // Enum is @Enumerated(STRING) -> stored uppercase, must match AuthMode constant exactly.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, :name, :url, 'generic', 'NONE', '{whc.test.*}', TRUE)
            """)
          .setParameter("name", name)
          .setParameter("url", wm.baseUrl() + path)
          .executeUpdate();
    }

    @Transactional
    void seedEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(1L, type, Map.of("k", "v")));
    }

    /** Seeds a failed (retryable) delivery dated one day ago, tied to the named endpoint. */
    @Transactional
    void seedStaleFailedDelivery(String endpointName, String eventType) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, :etype, '{"k":"v"}'::jsonb, NOW() - INTERVAL '1 day', 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = :name
            """)
          .setParameter("etype", eventType)
          .setParameter("name", endpointName)
          .executeUpdate();
    }

    @Transactional
    double attemptedEpochFor(String eventType) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT EXTRACT(EPOCH FROM attempted_at) FROM core.webhook_delivery "
            + "WHERE event_type = :t ORDER BY id DESC LIMIT 1")
            .setParameter("t", eventType).getSingleResult()).doubleValue();
    }

    @Test
    void retryRefreshesAttemptedAt() {
        wm.stubFor(post("/stamp").willReturn(aResponse().withStatus(200)));
        seedEndpoint("whc-stamp", "/stamp");
        seedStaleFailedDelivery("whc-stamp", "whc.test.stamp");

        double before = attemptedEpochFor("whc.test.stamp");
        TenantContext.runAsSystem(consumer::retryBatch);
        double after = attemptedEpochFor("whc.test.stamp");

        // The retry must restamp the row so time-ordered views (recentForEndpoint sorts
        // by attempted_at DESC) show it at the top, not frozen at the original attempt.
        assertTrue(after > before, "attemptedAt must advance on retry: before=" + before + " after=" + after);
        wm.verify(1, postRequestedFor(urlEqualTo("/stamp")));
    }

    @Test
    void failedDeliveryRetriedWithoutRedeliveringHealthyEndpoint() {
        wm.stubFor(post("/ok").willReturn(aResponse().withStatus(200)));
        wm.stubFor(post("/broken").willReturn(aResponse().withStatus(500)));
        seedEndpoint("whc-ok", "/ok");
        seedEndpoint("whc-broken", "/broken");
        seedEvent("whc.test.one");

        TenantContext.runAsSystem(() -> sweeper.sweep("webhook", 100, consumer::process));

        // both endpoints got attempt #1
        wm.verify(1, postRequestedFor(urlEqualTo("/ok")));
        wm.verify(1, postRequestedFor(urlEqualTo("/broken")));

        // heal the broken endpoint, run the retry sweep
        wm.stubFor(post("/broken").willReturn(aResponse().withStatus(200)));
        TenantContext.runAsSystem(consumer::retryBatch);

        // healthy endpoint NOT redelivered; broken one redelivered exactly once
        wm.verify(1, postRequestedFor(urlEqualTo("/ok")));
        wm.verify(2, postRequestedFor(urlEqualTo("/broken")));
        assertEquals(0, countFailedRetryable());
    }

    @Test
    void retryGivesUpAfterThreeAttempts() {
        wm.stubFor(post("/dead").willReturn(aResponse().withStatus(500)));
        seedEndpoint("whc-dead", "/dead");
        seedEvent("whc.test.dead");

        TenantContext.runAsSystem(() -> sweeper.sweep("webhook", 100, consumer::process)); // attempt 1
        TenantContext.runAsSystem(consumer::retryBatch);                                   // attempt 2
        TenantContext.runAsSystem(consumer::retryBatch);                                   // attempt 3
        TenantContext.runAsSystem(consumer::retryBatch);                                   // no-op

        wm.verify(3, postRequestedFor(urlEqualTo("/dead")));
        assertEquals(0, countFailedRetryable());
    }

    @Transactional
    long countFailedRetryable() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3 AND event_type LIKE 'whc.test.%'")
            .getSingleResult()).longValue();
    }
}
