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
 * A source that never answers with data must never be reported as a successful, empty sync.
 *
 * <p>Written from an observed run, not from a theory. Against a fake API answering 500 to every
 * request, a {@code rest-generic} connector reported:
 * {@code Completed — in=0 out=0 skipped=0 dead-letters=0, chunks=1, 15726 ms}, with no WARN and no
 * ERROR anywhere in the hub log. The retry executor did its job (five attempts), then handed the
 * 500 back as if it were a page; nothing downstream inspected the status; the error body carries no
 * items path, so the strategy read zero items and the chunk loop concluded the data was exhausted.
 *
 * <p>An operator watching run history would have seen a green row and an empty table, on the day
 * their upstream went down. That is the failure this IT exists to keep closed, at the only level
 * that can prove it: the run status the engine actually returns.
 */
@QuarkusTest
class DeadSourceIsNotAnEmptySyncIT {

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

    private UUID seed(String path, String table) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        String connJson = "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
            + "\",\"default_rate_limit\":\"100/s\"}";
        String epJson = "{\"pagination\":{\"kind\":\"page\",\"page_param\":\"page\",\"size_param\":\"size\",\"size\":2,"
            + "\"start_page\":1,\"empty_check\":true,\"response_path\":\"$.data\"},"
            + "\"incremental\":{\"mode\":\"full\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}";
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery(
                        "INSERT INTO connector.connector"
                        + "(id, tenant_id, org_id, name, type_id, config)"
                        + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId)
                    .setParameter("name", "dead-src-" + connId)
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
                try { utx.rollback(); } catch (Exception ignored) { /* best-effort: original propagates */ }
                throw new RuntimeException(e);
            }
        });
        return connId;
    }

    /**
     * The headline case. Slow by construction — the run spends the whole retry budget (five
     * attempts, exponential backoff) before the guard sees the 500, and that IS the behaviour under
     * test: retrying an upstream outage is right, calling it a success afterwards is not.
     */
    @Test
    void a_source_answering_500_to_everything_fails_the_run() {
        String path = "/v1/dead";
        String table = "ext.dead_500_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(500)
            .withHeader("Content-Type", "text/html")
            .withBody("<html><body>Internal Server Error</body></html>")));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(seed(path, table), new RunTrigger.Manual(1L)));

        RunStatus.Failed failed = assertInstanceOf(RunStatus.Failed.class, s,
            "a source that only ever answered 500 must not be reported as a successful sync");
        assertTrue(failed.message().contains("500"),
            "the failure must name the status the upstream returned, got: " + failed.message());
        assertTrue(failed.retryable(), "a 5xx outage is transient — the chunk deserves a later retry");
    }

    /**
     * The quiet twin: an expired credential. 401 is not retryable, so the run used to end in
     * milliseconds, green and empty — the shape most likely to be mistaken for "nothing changed
     * upstream today".
     */
    @Test
    void an_expired_credential_fails_the_run_instead_of_syncing_nothing() {
        String path = "/v1/locked";
        String table = "ext.dead_401_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"token expired\"}")));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(seed(path, table), new RunTrigger.Manual(1L)));

        RunStatus.Failed failed = assertInstanceOf(RunStatus.Failed.class, s,
            "an expired credential must not read as an empty upstream");
        assertTrue(failed.message().contains("401"),
            "the failure must name the status, got: " + failed.message());
        assertFalse(failed.retryable(),
            "a credential failure is permanent — retrying the chunk cannot fix it");
    }
}
