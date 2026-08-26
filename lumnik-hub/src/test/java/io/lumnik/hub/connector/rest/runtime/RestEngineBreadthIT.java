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
 * Breadth IT for the ConnectorEngine bridge: drives a {@code rest-generic} connector through
 * {@link ConnectorEngine#runSync} (NOT the direct {@code runtime.runEndpoint}) and asserts the
 * engine actually ingests real rows via the {@link RestEndpointReader}/{@link RestEndpointWriter}
 * bridge.
 */
@QuarkusTest
class RestEngineBreadthIT {

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

    /** Seeds a connector + one endpoint via native SQL (tenant 1 / org 0) and returns the connector id. */
    private UUID seed(String connJson, String path, String epJson) {
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
                    .setParameter("name", "engine-rt-" + connId)
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
        return connId;
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

    @Test
    void cursor_bearer_through_engine() {
        String table = "ext.c2_cursor_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo("/v1/items")).withQueryParam("starting_after", absent())
            .willReturn(okJson("{\"has_more\":true,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo("/v1/items")).withQueryParam("starting_after", equalTo("b"))
            .willReturn(okJson("{\"has_more\":false,\"data\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID id = seed(
            "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                + "\",\"auth\":{\"kind\":\"bearer\",\"token_value\":\"c2-token\"},\"default_rate_limit\":\"100/s\"}",
            "/v1/items",
            "{\"pagination\":{\"kind\":\"cursor\",\"cursor_param\":\"starting_after\",\"cursor_path\":\"$.data[-1:].id\","
                + "\"has_more_path\":\"$.has_more\",\"response_path\":\"$.data\"},"
                + "\"incremental\":{\"mode\":\"full\"},"
                + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(getRequestedFor(urlPathEqualTo("/v1/items")).withHeader("Authorization", equalTo("Bearer c2-token")));
    }

    /** offset pagination (limit=2; stop on n<limit / off+n>=total) + no auth. */
    @Test
    void offset_none() {
        String path = "/v1/off";
        String table = "ext.c2_offset_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("0")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":3,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("2")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":3,\"data\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID id = seed(
            "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                + "\",\"default_rate_limit\":\"100/s\"}",
            path,
            "{\"pagination\":{\"kind\":\"offset\",\"offset_param\":\"offset\",\"limit_param\":\"limit\",\"limit\":2,"
                + "\"total_path\":\"$.total\",\"response_path\":\"$.data\"},"
                + "\"incremental\":{\"mode\":\"full\"},"
                + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(getRequestedFor(urlPathEqualTo(path)).withoutHeader("Authorization"));
    }

    /** page pagination (has_more) + HTTP Basic auth header. */
    @Test
    void page_basic() {
        System.setProperty("C2_BASIC_PW", "p");
        try {
            String path = "/v1/page";
            String table = "ext.c2_page_" + Math.abs(System.nanoTime());
            stubFor(get(urlPathEqualTo(path)).withQueryParam("page", equalTo("1"))
                .willReturn(okJson("{\"has_more\":true,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
            stubFor(get(urlPathEqualTo(path)).withQueryParam("page", equalTo("2"))
                .willReturn(okJson("{\"has_more\":false,\"data\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

            UUID id = seed(
                "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                    + "\",\"auth\":{\"kind\":\"basic\",\"username\":\"u\",\"password_env\":\"C2_BASIC_PW\"},"
                    + "\"default_rate_limit\":\"100/s\"}",
                path,
                "{\"pagination\":{\"kind\":\"page\",\"page_param\":\"page\",\"size_param\":\"size\",\"size\":2,"
                    + "\"start_page\":1,\"has_more_path\":\"$.has_more\",\"response_path\":\"$.data\"},"
                    + "\"incremental\":{\"mode\":\"full\"},"
                    + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                    + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

            RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
            assertInstanceOf(RunStatus.Completed.class, s);
            assertEquals(3L, count(table));
            String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("u:p".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            verify(getRequestedFor(urlPathEqualTo(path)).withHeader("Authorization", equalTo(expected)));
        } finally {
            System.clearProperty("C2_BASIC_PW");
        }
    }

    /** Link rel="next" header pagination (absolute URL) + X-API-Key header. */
    @Test
    void link_header_apikey() {
        System.setProperty("C2_APIKEY", "k-123");
        try {
            String path = "/v1/lh";
            String table = "ext.c2_lh_" + Math.abs(System.nanoTime());
            String nextUrl = "http://localhost:" + wm.port() + path + "?cursor=p2";
            stubFor(get(urlPathEqualTo(path)).withQueryParam("cursor", absent())
                .willReturn(okJson("{\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")
                    .withHeader("Link", "<" + nextUrl + ">; rel=\"next\"")));
            stubFor(get(urlPathEqualTo(path)).withQueryParam("cursor", equalTo("p2"))
                .willReturn(okJson("{\"data\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

            UUID id = seed(
                "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                    + "\",\"auth\":{\"kind\":\"api-key\",\"in\":\"header\",\"name\":\"X-API-Key\",\"value_env\":\"C2_APIKEY\"},"
                    + "\"default_rate_limit\":\"100/s\"}",
                path,
                "{\"pagination\":{\"kind\":\"link-header\",\"response_path\":\"$.data\"},"
                    + "\"incremental\":{\"mode\":\"full\"},"
                    + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                    + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

            RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
            assertInstanceOf(RunStatus.Completed.class, s);
            assertEquals(3L, count(table));
            verify(getRequestedFor(urlPathEqualTo(path)).withHeader("X-API-Key", equalTo("k-123")));
        } finally {
            System.clearProperty("C2_APIKEY");
        }
    }

    /** GraphQL-relay edges/pageInfo pagination + HTTP Basic auth. */
    @Test
    void relay_basic() {
        System.setProperty("C2_BASIC_PW", "p");
        try {
            String path = "/v1/relay";
            String table = "ext.c2_relay_" + Math.abs(System.nanoTime());
            stubFor(get(urlPathEqualTo(path)).withQueryParam("after", absent())
                .willReturn(okJson("{\"data\":{\"edges\":[{\"node\":{\"id\":\"a\",\"name\":\"X\"}},"
                    + "{\"node\":{\"id\":\"b\",\"name\":\"Y\"}}],"
                    + "\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c1\"}}}")));
            stubFor(get(urlPathEqualTo(path)).withQueryParam("after", equalTo("c1"))
                .willReturn(okJson("{\"data\":{\"edges\":[{\"node\":{\"id\":\"c\",\"name\":\"Z\"}}],"
                    + "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"c2\"}}}")));

            UUID id = seed(
                "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                    + "\",\"auth\":{\"kind\":\"basic\",\"username\":\"u\",\"password_env\":\"C2_BASIC_PW\"},"
                    + "\"default_rate_limit\":\"100/s\"}",
                path,
                "{\"pagination\":{\"kind\":\"relay\",\"cursor_param\":\"after\","
                    + "\"cursor_path\":\"$.data.pageInfo.endCursor\",\"has_more_path\":\"$.data.pageInfo.hasNextPage\","
                    + "\"cursor_binding\":\"query\",\"response_path\":\"$.data.edges[*].node\"},"
                    + "\"incremental\":{\"mode\":\"full\"},"
                    + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                    + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

            RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
            assertInstanceOf(RunStatus.Completed.class, s);
            assertEquals(3L, count(table));
            String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("u:p".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            verify(getRequestedFor(urlPathEqualTo(path)).withHeader("Authorization", equalTo(expected)));
        } finally {
            System.clearProperty("C2_BASIC_PW");
        }
    }
}
