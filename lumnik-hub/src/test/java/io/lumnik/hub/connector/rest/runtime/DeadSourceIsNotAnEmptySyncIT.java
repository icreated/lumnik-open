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

import java.util.List;
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
        return seed(path, table, "http://localhost:" + wm.port());
    }

    private UUID seed(String path, String table, String baseUrl) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        String connJson = "{\"kind\":\"rest-generic\",\"base_url\":\"" + baseUrl
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

    // ── what the run's failure text may quote (#1166) ─────────────────────────────────────────

    private static final List<String> SECRETS = List.of("user", "pw", "ECRET", "k=v");

    /**
     * The text the run row stores — what `:runs` and `lm connector runs` show. Read inside a
     * transaction: the tenant setting that RLS reads is transaction-local.
     */
    private String storedFailure(UUID connectorId) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                return (String) em.createNativeQuery(
                        "SELECT error_message FROM connector.connector_run WHERE connector_id = :c")
                    .setParameter("c", connectorId).getSingleResult();
            } finally {
                utx.commit();
            }
        });
    }

    private void assertQuotesNothingOfTheSource(String text) {
        for (String secret : SECRETS) {
            assertFalse(text.contains(secret), secret + " in: " + text);
        }
    }

    /**
     * A redirect's {@code Location} can carry a token or a signed query: the run names its host,
     * never the rest of it.
     */
    @Test
    void a_redirect_names_its_host_in_the_run_failure_never_its_path_or_query() {
        String path = "/v1/moved";
        String table = "ext.dead_302_" + Math.abs(System.nanoTime());
        stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(302)
            .withHeader("Location", "https://user:pw@api.example/p/SECRET?k=v")));

        UUID connectorId = seed(path, table);
        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(connectorId, new RunTrigger.Manual(1L)));

        RunStatus.Failed failed = assertInstanceOf(RunStatus.Failed.class, s);
        String expected = "upstream returned a redirect (302) to https://api.example"
            + " — refusing to follow it or ingest its body as data";
        assertEquals(expected, failed.message());
        assertEquals(expected, storedFailure(connectorId));
    }

    /**
     * A source nobody answers on: the run says the failure's kind, never the JDK message, which
     * can quote the URL. Port 1 on localhost is never bound — nothing leaves the machine. Slow by
     * construction, like the 500 case: a network failure spends the retry budget first.
     */
    @Test
    void a_source_nobody_answers_on_is_stored_by_its_kind() {
        UUID connectorId = seed("/p/SECRET?k=v", "ext.dead_refused_" + Math.abs(System.nanoTime()),
            "http://user:pw@localhost:1");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(connectorId, new RunTrigger.Manual(1L)));

        RunStatus.Failed failed = assertInstanceOf(RunStatus.Failed.class, s);
        assertEquals("connection failed (ConnectException)", failed.message());
        String stored = storedFailure(connectorId);
        assertEquals("connection failed (ConnectException)", stored);
        assertQuotesNothingOfTheSource(stored);
    }
}
