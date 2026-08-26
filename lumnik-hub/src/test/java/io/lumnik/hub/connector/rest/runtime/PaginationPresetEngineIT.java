package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.connector.handler.RestConnectorApplyHandler;
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
 * Preset expansion through the REAL apply front door, then ingestion via the engine.
 * Proves: manifest pagination.kind="drf-page" expands to body-link and follows $.next;
 *          manifest pagination.kind="spring-page" expands to page and uses $.totalPages.
 */
@QuarkusTest
class PaginationPresetEngineIT {

    @Inject RestConnectorApplyHandler applyHandler;
    @Inject ConnectorEngine engine;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private static final ObjectMapper M = new ObjectMapper();

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

    /** Apply manifest through the real handler (needs a JTA transaction, same as the resource method). */
    private UUID apply(String manifestJson) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                var res = applyHandler.apply(M.readTree(manifestJson), 1L);
                utx.commit();
                return res.connectorId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void drf_page_preset_paginates_via_body_link() {
        String table = "ext.preset_drf_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();

        // page 1: has a "next" link pointing to page 2
        stubFor(get(urlPathEqualTo("/api/items"))
            .withQueryParam("page", absent())
            .willReturn(okJson("{\"count\":3,\"next\":\"" + base + "/api/items?page=2\",\"results\":["
                + "{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        // page 2: next is null → stop
        stubFor(get(urlPathEqualTo("/api/items"))
            .withQueryParam("page", equalTo("2"))
            .willReturn(okJson("{\"count\":3,\"next\":null,\"results\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID id = apply("{\"metadata\":{\"name\":\"drf-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"items\",\"path\":\"/api/items\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"drf-page\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
    }

    @Test
    void spring_page_preset_paginates_via_page() {
        String table = "ext.preset_spring_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();

        // spring-page expands to page (start_page=0, total_pages_path=$.totalPages). With totalPages=2
        // the valid pages are 0 and 1; the loop stops after page 1 (pages-fetched == totalPages) — it
        // must NOT request a non-existent page 2 (verified below).
        stubFor(get(urlPathEqualTo("/api/items"))
            .withQueryParam("page", equalTo("0"))
            .willReturn(okJson("{\"totalPages\":2,\"content\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo("/api/items"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(okJson("{\"totalPages\":2,\"content\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID id = apply("{\"metadata\":{\"name\":\"spring-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"items\",\"path\":\"/api/items\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"spring-page\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        // no over-fetch: 0-indexed page stops after the last valid page (1), never requests page 2.
        verify(0, getRequestedFor(urlPathEqualTo("/api/items")).withQueryParam("page", equalTo("2")));
    }
}
