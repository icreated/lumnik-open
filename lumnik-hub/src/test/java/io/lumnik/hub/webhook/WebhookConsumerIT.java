package io.lumnik.hub.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.EventConsumerPosition;
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
        seedEndpoint(name, path, "whc.test.*");
    }

    @Transactional
    void seedEndpoint(String name, String path, String eventPattern) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // auth_mode NONE: no secret needed, the HTTP POST fires unconditionally (the V5 'secret'
        // plaintext column was dropped; HMAC mode requires secret_encrypted which we don't seed).
        // Enum is @Enumerated(STRING) -> stored uppercase, must match AuthMode constant exactly.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, :name, :url, 'generic', 'NONE', ARRAY[CAST(:pattern AS text)], TRUE)
            """)
          .setParameter("name", name)
          .setParameter("url", wm.baseUrl() + path)
          .setParameter("pattern", eventPattern)
          .executeUpdate();
    }

    @Transactional
    long seedEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        EventOutboxRow row = EventOutboxRow.of(1L, type, Map.of("k", "v"));
        em.persist(row);
        return row.getId();
    }

    /** Seeds a failed (retryable) delivery dated one day ago, tied to the named endpoint.
     *  {@code eventId} may be null — only the correlation test needs one set. */
    @Transactional
    void seedStaleFailedDelivery(String endpointName, String eventType, Long eventId) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, event_id, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, :etype, :eventId, '{"k":"v"}'::jsonb, NOW() - INTERVAL '1 day', 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = :name
            """)
          .setParameter("etype", eventType)
          .setParameter("eventId", eventId)
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
        seedStaleFailedDelivery("whc-stamp", "whc.test.stamp", null);

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

        TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor));

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
    void initialDeliveryCarriesTheOutboxEventIdHeader() {
        wm.stubFor(post("/eventid").willReturn(aResponse().withStatus(200)));
        seedEndpoint("whc-eventid", "/eventid");
        long eventId = seedEvent("whc.test.eventid");

        TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor));

        // The header must carry the SAME id core.event_outbox assigned this event — the id the
        // inbox later echoes back as `decision_id` in the annotation.decision.acked event, so a
        // receiver can correlate a decision.* delivery with its later ack without calling back.
        wm.verify(1, postRequestedFor(urlEqualTo("/eventid"))
            .withHeader("X-Lumnik-Event-Id", equalTo(String.valueOf(eventId))));
    }

    @Test
    void retryCarriesTheSameEventIdAsTheOriginalDelivery() {
        wm.stubFor(post("/retry-eventid").willReturn(aResponse().withStatus(500)));
        seedEndpoint("whc-retry-eventid", "/retry-eventid");
        seedStaleFailedDelivery("whc-retry-eventid", "whc.test.retry-eventid", 424242L);

        TenantContext.runAsSystem(consumer::retryBatch);

        wm.verify(1, postRequestedFor(urlEqualTo("/retry-eventid"))
            .withHeader("X-Lumnik-Event-Id", equalTo("424242")));
    }

    @Test
    void retryGivesUpAfterThreeAttempts() {
        wm.stubFor(post("/dead").willReturn(aResponse().withStatus(500)));
        seedEndpoint("whc-dead", "/dead");
        seedEvent("whc.test.dead");

        TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor)); // attempt 1
        TenantContext.runAsSystem(consumer::retryBatch);                                   // attempt 2
        TenantContext.runAsSystem(consumer::retryBatch);                                   // attempt 3
        TenantContext.runAsSystem(consumer::retryBatch);                                   // no-op

        wm.verify(3, postRequestedFor(urlEqualTo("/dead")));
        assertEquals(0, countFailedRetryable());
    }

    /**
     * #943: a NUL byte in a response body failed the webhook_delivery INSERT on the sweep's batch
     * transaction, so every tick rolled the cursor back and re-POSTed the batch. Three sweeps, as
     * in the issue's executed reproduction.
     */
    @Test
    void aNulByteInAResponseBodyNeitherStallsTheCursorNorRepostsTheBatch() {
        wm.stubFor(post("/nul-ok").willReturn(aResponse().withStatus(200).withBody("fine")));
        wm.stubFor(post("/nul-bad").willReturn(aResponse().withStatus(200).withBody("x\u0000y")));
        seedEndpoint("whc-nul-ok", "/nul-ok", "whc.test.nul.ok.*");
        seedEndpoint("whc-nul-bad", "/nul-bad", "whc.test.nul.bad.*");
        seedEvent("whc.test.nul.ok.first");
        seedEvent("whc.test.nul.bad.one");
        long last = seedEvent("whc.test.nul.ok.after");

        for (int sweep = 0; sweep < 3; sweep++) {
            TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor));
        }

        assertEquals(last, webhookCursor(), "the cursor must advance past the whole batch");
        wm.verify(1, postRequestedFor(urlEqualTo("/nul-ok"))
            .withHeader("X-Lumnik-Event", equalTo("whc.test.nul.ok.first")));
        wm.verify(1, postRequestedFor(urlEqualTo("/nul-bad")));
        wm.verify(1, postRequestedFor(urlEqualTo("/nul-ok"))
            .withHeader("X-Lumnik-Event", equalTo("whc.test.nul.ok.after")));
        assertEquals("x\uFFFDy", responseBodyFor("whc.test.nul.bad.one"));
    }

    /**
     * #943 on the retry path: the outcome is written in its own short transaction, so a NUL there
     * left the claim standing and the attempt uncounted — the row was re-POSTed after every lease
     * expiry, forever. It must count and release like any failed attempt.
     */
    @Test
    void aNulByteInARetryResponseIsRecordedLikeAnyFailedAttempt() {
        wm.stubFor(post("/nul-retry").willReturn(aResponse().withStatus(500).withBody("x\u0000y")));
        seedEndpoint("whc-nul-retry", "/nul-retry");
        seedStaleFailedDelivery("whc-nul-retry", "whc.test.nul.retry", null);

        TenantContext.runAsSystem(consumer::retryBatch);

        Object[] row = retryOutcomeFor("whc.test.nul.retry");
        assertEquals(2, ((Number) row[0]).intValue(), "attempts");
        assertEquals("HTTP 500: x\uFFFDy", row[1]);
        assertEquals(Boolean.TRUE, row[2], "claim released");
    }

    @Transactional
    Object[] retryOutcomeFor(String eventType) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery(
            "SELECT attempts, error, claimed_until IS NULL FROM core.webhook_delivery WHERE event_type = :t")
            .setParameter("t", eventType).getSingleResult();
    }

    @Transactional
    long webhookCursor() {
        return em.find(EventConsumerPosition.class, "webhook").getLastEventId();
    }

    @Transactional
    String responseBodyFor(String eventType) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (String) em.createNativeQuery(
            "SELECT response_body FROM core.webhook_delivery WHERE event_type = :t")
            .setParameter("t", eventType).getSingleResult();
    }

    @Transactional
    long countFailedRetryable() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3 AND event_type LIKE 'whc.test.%'")
            .getSingleResult()).longValue();
    }
}
