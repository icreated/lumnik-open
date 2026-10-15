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

import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EntityFilterDeliveryIT {

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
        // Empty the whole log so the freshly-seeded rows fall within the single manual sweep's
        // batch window (id > cursor LIMIT n); same accepted pattern as WebhookConsumerIT.
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'webhook'").executeUpdate();
        // Deliveries FK-reference the ef-*/efr-* endpoints; drop them before the endpoints they point at.
        em.createNativeQuery("""
            DELETE FROM core.webhook_delivery
            WHERE endpoint_id IN (SELECT id FROM core.webhook_endpoint WHERE name LIKE 'ef-%' OR name LIKE 'efr-%')
            """).executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'ef-%' OR name LIKE 'efr-%'").executeUpdate();
        // The retry sweep claims globally (all tenants/types) — the retry tests assert global POST counts,
        // so remove ANY retryable row other tests may have left behind, like WebhookRetryThrowIT does.
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
    }

    /** Seeds an enabled endpoint subscribed to one exact event type, with the given entity_filter. */
    @Transactional
    void seedEndpoint(String name, String path, String eventTypes, String entityFilter) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint
                (tenant_id, name, target_url, platform, auth_mode, event_types, enabled, entity_filter)
            VALUES (1, :name, :url, 'generic', 'NONE', CAST(:types AS text[]), TRUE, :filter)
            """)
          .setParameter("name", name)
          .setParameter("url", wm.baseUrl() + path)
          .setParameter("types", eventTypes)
          .setParameter("filter", entityFilter)
          .executeUpdate();
    }

    @Transactional
    void persistRow(EventOutboxRow row) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(row);
    }

    @Test
    void entityFilterNarrowsDataEventDelivery() {
        wm.stubFor(post("/match").willReturn(aResponse().withStatus(200)));
        wm.stubFor(post("/other").willReturn(aResponse().withStatus(200)));
        wm.stubFor(post("/all").willReturn(aResponse().withStatus(200)));
        seedEndpoint("ef-match", "/match", "{data.row.changed}", "erp.fact");
        seedEndpoint("ef-other", "/other", "{data.row.changed}", "erp.other");
        seedEndpoint("ef-all", "/all", "{data.row.changed}", null);

        persistRow(EventOutboxRow.ofFull(1L, null, "data.row.changed", "test",
                "erp.fact", "h1", Map.of("k", "v"), null, Instant.now()));

        TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor));

        // entity erp.fact: the matching-filter endpoint and the unfiltered endpoint fire; the
        // mismatched-filter endpoint is excluded.
        wm.verify(1, postRequestedFor(urlEqualTo("/match")));
        wm.verify(1, postRequestedFor(urlEqualTo("/all")));
        wm.verify(0, postRequestedFor(urlEqualTo("/other")));
    }

    @Test
    void entityLessEventReachesFilteredEndpoint() {
        wm.stubFor(post("/runfilter").willReturn(aResponse().withStatus(200)));
        seedEndpoint("ef-runfilter", "/runfilter", "{connector.run.completed}", "erp.fact");

        // of(...) sets entity=null: an entity-less event must not be blocked by entity_filter.
        persistRow(EventOutboxRow.of(1L, "connector.run.completed", Map.of("k", "v")));

        TenantContext.runAsSystem(() -> sweeper.drain("webhook", 100, consumer::batchProcessor));

        wm.verify(1, postRequestedFor(urlEqualTo("/runfilter")));
    }

    /** Directly seeds a failed (retryable) delivery for the named endpoint with the given payload. */
    @Transactional
    void seedRetryableDelivery(String endpointName, String eventType, String payloadJson) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            SELECT 1, e.id, :type, CAST(:payload AS jsonb), NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = :name
            """)
          .setParameter("type", eventType)
          .setParameter("payload", payloadJson)
          .setParameter("name", endpointName)
          .executeUpdate();
    }

    @Transactional
    Object[] deliveryRow(String endpointName) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery("""
            SELECT d.attempts, d.succeeded FROM core.webhook_delivery d
            JOIN core.webhook_endpoint e ON e.id = d.endpoint_id
            WHERE e.name = :name
            """).setParameter("name", endpointName).getSingleResult();
    }

    @Test
    void retryStopsWhenEntityFilterNoLongerMatches() {
        // The endpoint's filter was narrowed to erp.other AFTER a failed delivery whose payload
        // entity is erp.fact. The retry must re-apply entity_filter and NOT redeliver.
        wm.stubFor(post("/stale").willReturn(aResponse().withStatus(200)));
        seedEndpoint("efr-stale", "/stale", "{data.row.changed}", "erp.other");
        seedRetryableDelivery("efr-stale", "data.row.changed",
            "{\"entity\":\"erp.fact\",\"entity_key\":\"h1\",\"op\":\"insert\"}");

        TenantContext.runAsSystem(consumer::retryBatch);

        wm.verify(0, postRequestedFor(urlEqualTo("/stale")));
        Object[] row = deliveryRow("efr-stale");
        assertEquals(3, ((Number) row[0]).intValue(), "entity_filter mismatch must stop retries (attempts=3)");
        assertEquals(Boolean.FALSE, row[1], "succeeded stays false");
    }

    @Test
    void retryProceedsWhenEntityFilterStillMatches() {
        // Positive control: payload entity erp.fact still matches the endpoint filter erp.fact,
        // so the retry redelivers and the (200) response marks the delivery succeeded.
        wm.stubFor(post("/ok").willReturn(aResponse().withStatus(200)));
        seedEndpoint("efr-ok", "/ok", "{data.row.changed}", "erp.fact");
        seedRetryableDelivery("efr-ok", "data.row.changed",
            "{\"entity\":\"erp.fact\",\"entity_key\":\"h1\",\"op\":\"insert\"}");

        TenantContext.runAsSystem(consumer::retryBatch);

        wm.verify(1, postRequestedFor(urlEqualTo("/ok")));
        Object[] row = deliveryRow("efr-ok");
        assertEquals(Boolean.TRUE, row[1], "matching entity_filter retry redelivers and succeeds");
    }
}
