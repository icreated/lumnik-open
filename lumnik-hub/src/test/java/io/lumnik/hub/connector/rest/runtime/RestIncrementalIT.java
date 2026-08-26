package io.lumnik.hub.connector.rest.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Incremental + cross-run IT for the {@code rest-generic} engine bridge.
 *
 * <p>Proves two things the {@link RestEndpointReader} cursor change fixes:
 * <ol>
 *   <li><b>FULL re-run idempotency</b> — re-running the SAME connector/endpoint re-reads everything
 *       (runId-discriminated cursor), not the latent {@code {"phase":"done"}} short-circuit that
 *       would ingest 0 on the second run.</li>
 *   <li><b>WATERMARK incremental</b> — the stored watermark is sent as the configured query param on
 *       the next run's first request, so only the delta is fetched; the new watermark is persisted.</li>
 * </ol>
 */
@QuarkusTest
class RestIncrementalIT {

    @Inject ConnectorEngine engine;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    /** Seeds a connector + one endpoint via native SQL (tenant 1 / org 0) and returns the connector + endpoint ids. */
    private UUID[] seed(String connJson, String path, String epJson) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery(
                        "INSERT INTO connector.connector"
                        + "(id, tenant_id, org_id, name, type_id, config)"
                        + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId)
                    .setParameter("name", "inc-rt-" + connId)
                    .setParameter("cfg", connJson)
                    .executeUpdate();
                em.createNativeQuery(
                        "INSERT INTO connector.connector_endpoint"
                        + "(id, connector_id, tenant_id, org_id, name, path, config)"
                        + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                    .setParameter("id", epId)
                    .setParameter("connId", connId)
                    .setParameter("name", "items-" + epId)
                    .setParameter("path", path)
                    .setParameter("cfg", epJson)
                    .executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
        return new UUID[]{connId, epId};
    }

    private long count(String table) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                Number n = (Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
                return n.longValue();
            } finally {
                utx.commit();
            }
        });
    }

    private String persistedWatermark(UUID endpointId) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                Object v = em.createNativeQuery(
                        "SELECT cursor_value ->> 'watermark' FROM connector.connector_cursor WHERE endpoint_id = :ep")
                    .setParameter("ep", endpointId)
                    .getSingleResult();
                return v == null ? null : v.toString();
            } finally {
                utx.commit();
            }
        });
    }

    /** A) FULL re-run idempotency — the second runSync must re-read + upsert (count stays 3, not 0). */
    @Test
    void full_rerun_is_idempotent() {
        String path = "/v1/full";
        String table = "ext.c2_full_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("0")).withQueryParam("limit", equalTo("100"))
            .willReturn(okJson("{\"total\":3,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"},{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID[] ids = seed(
            "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                + "\",\"default_rate_limit\":\"100/s\"}",
            path,
            "{\"pagination\":{\"kind\":\"offset\",\"offset_param\":\"offset\",\"limit_param\":\"limit\",\"limit\":100,"
                + "\"total_path\":\"$.total\",\"response_path\":\"$.data\"},"
                + "\"incremental\":{\"mode\":\"full\"},"
                + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");
        UUID connId = ids[0];

        RunStatus s1 = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s1);
        assertEquals(3L, count(table), "first run should ingest 3");

        // Re-run the SAME connector/endpoint. The latent {"phase":"done"} cursor would make this 0.
        RunStatus s2 = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s2);
        assertEquals(3L, count(table), "FULL re-run must re-read + upsert — count stays 3, not 0");
    }

    /** B + C) WATERMARK incremental delta, then idempotent re-run with no new data. */
    @Test
    void watermark_incremental_delta_then_idempotent() {
        String path = "/v1/wm";
        String table = "ext.c2_wm_" + Math.abs(System.nanoTime());

        // Run 1: no updated_since param → a,b (max updated_at = 2026-01-02).
        stubFor(get(urlPathEqualTo(path)).withQueryParam("updated_since", absent())
            .willReturn(okJson("{\"data\":[{\"id\":\"a\",\"name\":\"X\",\"updated_at\":\"2026-01-01\"},"
                + "{\"id\":\"b\",\"name\":\"Y\",\"updated_at\":\"2026-01-02\"}]}")));
        // Run 2: request carries updated_since=2026-01-02 → only c (updated_at 2026-01-03).
        stubFor(get(urlPathEqualTo(path)).withQueryParam("updated_since", equalTo("2026-01-02"))
            .willReturn(okJson("{\"data\":[{\"id\":\"c\",\"name\":\"Z\",\"updated_at\":\"2026-01-03\"}]}")));

        UUID[] ids = seed(
            "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                + "\",\"default_rate_limit\":\"100/s\"}",
            path,
            "{\"pagination\":{\"kind\":\"page\",\"page_param\":\"page\",\"size_param\":\"size\",\"size\":100,"
                + "\"start_page\":1,\"has_more_path\":\"$.has_more\",\"response_path\":\"$.data\"},"
                + "\"incremental\":{\"mode\":\"watermark\",\"watermark_path\":\"$.updated_at\",\"param\":\"updated_since\"},"
                + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");
        UUID connId = ids[0];
        UUID epId = ids[1];

        // Run 1.
        RunStatus s1 = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s1);
        assertEquals(2L, count(table), "run 1 ingests a,b");
        assertEquals("2026-01-02", persistedWatermark(epId), "watermark = MAX(updated_at) of run 1");

        // Run 2: delta exactly 1 (only c, behind the stored watermark).
        RunStatus s2 = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s2);
        assertEquals(3L, count(table), "run 2 adds exactly c → 3 total");
        assertEquals("2026-01-03", persistedWatermark(epId), "watermark advances to 2026-01-03");
        // The run-2 first request must have carried the stored watermark.
        verify(getRequestedFor(urlPathEqualTo(path)).withQueryParam("updated_since", equalTo("2026-01-02")));

        // Run 3 (C): no new records behind the advanced watermark → count stays 3.
        wm.resetAll();
        stubFor(get(urlPathEqualTo(path)).withQueryParam("updated_since", equalTo("2026-01-03"))
            .willReturn(okJson("{\"data\":[]}")));
        RunStatus s3 = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s3);
        assertEquals(3L, count(table), "run 3 (no new data) leaves count at 3");
        assertEquals("2026-01-03", persistedWatermark(epId), "watermark unchanged when no items");
    }
}
