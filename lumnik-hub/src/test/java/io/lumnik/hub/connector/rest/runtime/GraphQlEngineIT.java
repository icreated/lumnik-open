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

/** End-to-end GraphQL: POST {query,variables}, relay cursor in variables, header on the wire, errors->fail. */
@QuarkusTest
class GraphQlEngineIT {

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

    private UUID seed(String connJson, String path, String epJson) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery(
                        "INSERT INTO connector.connector(id, tenant_id, org_id, name, type_id, config)"
                        + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId)
                    .setParameter("name", "gql-" + connId)
                    .setParameter("cfg", connJson)
                    .executeUpdate();
                em.createNativeQuery(
                        "INSERT INTO connector.connector_endpoint(id, connector_id, tenant_id, org_id, name, path, config)"
                        + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                    .setParameter("id", epId)
                    .setParameter("connId", connId)
                    .setParameter("name", "orders-" + epId)
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

    private static final String QUERY =
        "query Orders($after:String){ orders(first:2, after:$after){ "
        + "edges{ node{ id name } } pageInfo{ hasNextPage endCursor } } }";

    private String connJson() {
        return "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port() + "\","
            + "\"graphql_path\":\"/api/graphql\",\"default_rate_limit\":\"100/s\","
            + "\"headers\":[{\"name\":\"X-Api-Version\",\"value\":\"2024-10\"}]}";
    }

    private String epJson(String table) {
        return "{\"kind\":\"graphql\",\"query\":\"" + QUERY.replace("\"", "\\\"") + "\","
            + "\"pagination\":{\"kind\":\"relay\",\"cursor_binding\":\"variable\",\"cursor_var\":\"after\","
            + "\"cursor_path\":\"$.data.orders.pageInfo.endCursor\","
            + "\"has_more_path\":\"$.data.orders.pageInfo.hasNextPage\","
            + "\"response_path\":\"$.data.orders.edges[*].node\"},"
            + "\"incremental\":{\"mode\":\"full\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}";
    }

    @Test
    void graphql_relay_paging_ingests_all_nodes_with_header() {
        String table = "ext.gql_orders_" + Math.abs(System.nanoTime());
        stubFor(post(urlPathEqualTo("/api/graphql"))
            .withRequestBody(matchingJsonPath("$.variables.after", absent()))
            .willReturn(okJson("{\"data\":{\"orders\":{\"edges\":["
                + "{\"node\":{\"id\":\"a\",\"name\":\"X\"}},{\"node\":{\"id\":\"b\",\"name\":\"Y\"}}],"
                + "\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c1\"}}}}")));
        stubFor(post(urlPathEqualTo("/api/graphql"))
            .withRequestBody(matchingJsonPath("$.variables.after", equalTo("c1")))
            .willReturn(okJson("{\"data\":{\"orders\":{\"edges\":["
                + "{\"node\":{\"id\":\"c\",\"name\":\"Z\"}}],"
                + "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"c2\"}}}}")));

        UUID id = seed(connJson(), "", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(postRequestedFor(urlPathEqualTo("/api/graphql"))
            .withHeader("X-Api-Version", equalTo("2024-10")));
        verify(postRequestedFor(urlPathEqualTo("/api/graphql"))
            .withHeader("Content-Type", containing("application/json")));
        verify(postRequestedFor(urlPathEqualTo("/api/graphql"))
            .withRequestBody(matchingJsonPath("$.query", containing("orders"))));
    }

    /**
     * GraphQL errors[] causes GraphQlResponseValidator to throw RuntimeException → ChunkExecutor
     * wraps it as ChunkResult.Failure → ConnectorEngine.failRun → RunStatus.Failed.
     */
    @Test
    void graphql_errors_array_fails_the_run() {
        String table = "ext.gql_err_" + Math.abs(System.nanoTime());
        stubFor(post(urlPathEqualTo("/api/graphql"))
            .willReturn(okJson("{\"errors\":[{\"message\":\"field 'orders' not found\"}],\"data\":null}")));

        UUID id = seed(connJson(), "", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Failed.class, s);
        assertTrue(((RunStatus.Failed) s).message().contains("error"),
            "Expected error message to mention 'error', got: " + ((RunStatus.Failed) s).message());
    }
}
