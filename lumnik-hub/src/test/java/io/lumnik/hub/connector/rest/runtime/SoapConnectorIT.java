package io.lumnik.hub.connector.rest.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end SOAP: POST envelope, offset paging (StartRowNum), LastPage stop, force-list, Fault->fail. */
@QuarkusTest
class SoapConnectorIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRunRepository runs;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(0); wm.start(); configureFor("localhost", wm.port()); }
    @AfterAll static void stop() { if (wm != null) wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private UUID seed(String connJson, String path, String epJson) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery(
                    "INSERT INTO connector.connector(id, tenant_id, org_id, name, type_id, config)"
                    + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId).setParameter("name", "soap-" + connId)
                    .setParameter("cfg", connJson).executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO connector.connector_endpoint(id, connector_id, tenant_id, org_id, name, path, config)"
                    + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                    .setParameter("id", epId).setParameter("connId", connId)
                    .setParameter("name", "customers-" + epId).setParameter("path", path)
                    .setParameter("cfg", epJson).executeUpdate();
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
            try {
                utx.begin();
                Number n = (Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
                long result = n.longValue();
                utx.commit();
                return result;
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
    }

    private String connJson() {
        return "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port() + "\","
            + "\"default_rate_limit\":\"100/s\"}";
    }

    private String epJson(String table) { return epJson(table, ""); }

    /** {@code extra} is spliced in as more endpoint keys, each ending with a comma. */
    private String epJson(String table, String extra) {
        String body = "<In><PageSize>{{page_size}}</PageSize><StartRowNum>{{cursor}}</StartRowNum></In>";
        return "{\"kind\":\"soap\",\"soap_action\":\"CustomerQueryPage\"," + extra
            + "\"body\":\"" + body.replace("\"", "\\\"") + "\","
            + "\"pagination\":{\"kind\":\"soap\",\"page_size\":2,"
            + "\"last_page_path\":\"$.Body.Out.LastPage\","
            + "\"response_path\":\"$.Body.Out.ListOfCustomer.Customer\"},"
            + "\"incremental\":{\"mode\":\"full\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.Id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.Name\",\"type\":\"text\"}]}}";
    }

    private static String envelope(String inner) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + inner + "</soap:Body></soap:Envelope>";
    }

    @Test
    void soap_offset_paging_ingests_all_rows_and_forces_single_element_to_a_row() {
        String table = "ext.soap_cust_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Page 1 (StartRowNum=0): two customers, not the last page.
        stubFor(post(urlPathEqualTo("/eai"))
            .withRequestBody(containing("<StartRowNum>0</StartRowNum>"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer>"
                + "<Customer><Id>a</Id><Name>X</Name></Customer>"
                + "<Customer><Id>b</Id><Name>Y</Name></Customer>"
                + "</ListOfCustomer><LastPage>false</LastPage></Out>"))));
        // Page 2 (StartRowNum=2): ONE customer (force-list path), last page.
        stubFor(post(urlPathEqualTo("/eai"))
            .withRequestBody(containing("<StartRowNum>2</StartRowNum>"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer>"
                + "<Customer><Id>c</Id><Name>Z</Name></Customer>"
                + "</ListOfCustomer><LastPage>true</LastPage></Out>"))));

        UUID id = seed(connJson(), "/eai", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        assertEquals(3L, count(table)); // 2 + 1 (the single element was forced into a row)

        verify(postRequestedFor(urlPathEqualTo("/eai"))
            .withHeader("Content-Type", containing("text/xml"))
            .withHeader("SOAPAction", equalTo("\"CustomerQueryPage\""))
            .withRequestBody(containing("<soap:Envelope")));
    }

    /** A body with no paging placeholder is the same request every page: it is sent once, whatever
     *  the stop flag says, instead of being re-read up to the page cap (#1387). */
    @Test
    void the_same_request_is_never_sent_twice_in_a_run() {
        String table = "ext.soap_once_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        stubFor(post(urlPathEqualTo("/eai"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer>"
                + "<Customer><Id>a</Id><Name>X</Name></Customer>"
                + "<Customer><Id>b</Id><Name>Y</Name></Customer>"
                + "</ListOfCustomer><LastPage>false</LastPage></Out>"))));

        UUID id = seed(connJson(), "/eai", epJson(table).replace("<StartRowNum>{{cursor}}</StartRowNum>", "<page>1</page>"));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        assertEquals(2L, count(table));
        verify(1, postRequestedFor(urlPathEqualTo("/eai")));
    }

    /** The same holds when auth signs each request: the guard compares requests before signing. */
    @Test
    void the_same_request_is_never_sent_twice_under_auth() {
        String table = "ext.soap_once_auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = "SOAP_BASIC_" + System.nanoTime();
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"pw\"}")
            .post("/api/platform/secrets").then().statusCode(201);
        stubFor(post(urlPathEqualTo("/eai"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer><Customer><Id>a</Id><Name>X</Name></Customer></ListOfCustomer>"
                + "<LastPage>false</LastPage></Out>"))));
        String conn = connJson().replace("}", ",\"auth\":{\"kind\":\"basic\",\"username\":\"u\",\"password_env\":\"" + name + "\"}}");

        UUID id = seed(conn, "/eai", epJson(table).replace("<StartRowNum>{{cursor}}</StartRowNum>", "<page>1</page>"));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        verify(1, postRequestedFor(urlPathEqualTo("/eai")).withHeader("Authorization", containing("Basic ")));
    }

    /** {@code {{secret:NAME}}} reads the lm secret registry, like every other credential (#1246). */
    @Test
    void a_secret_placeholder_reads_the_secret_registry() {
        String table = "ext.soap_sec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String name = "SOAP_PWD_" + System.nanoTime();
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"from-registry\"}")
            .post("/api/platform/secrets").then().statusCode(201);
        stubFor(post(urlPathEqualTo("/eai")).willReturn(aResponse()
            .withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer><Customer><Id>a</Id><Name>X</Name></Customer></ListOfCustomer>"
                + "<LastPage>true</LastPage></Out>"))));

        UUID id = seed(connJson(), "/eai",
            epJson(table, "\"header\":\"<Password>{{secret:" + name + "}}</Password>\","));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        verify(postRequestedFor(urlPathEqualTo("/eai"))
            .withRequestBody(containing("<Password>from-registry</Password>")));
    }

    /** A connector header's {@code value_env} reads the lm secret registry, like the auth fields. */
    @Test
    void a_header_value_env_reads_the_secret_registry() {
        String table = "ext.soap_hdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String name = "SOAP_HDR_" + System.nanoTime();
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"from-registry\"}")
            .post("/api/platform/secrets").then().statusCode(201);
        stubFor(post(urlPathEqualTo("/eai")).willReturn(aResponse()
            .withHeader("Content-Type", "text/xml").withBody(envelope(
                "<Out><ListOfCustomer><Customer><Id>a</Id><Name>X</Name></Customer></ListOfCustomer>"
                + "<LastPage>true</LastPage></Out>"))));
        String conn = connJson().replace("}", ",\"headers\":[{\"name\":\"X-Client-Id\",\"value_env\":\"" + name + "\"}]}");

        UUID id = seed(conn, "/eai", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        verify(postRequestedFor(urlPathEqualTo("/eai")).withHeader("X-Client-Id", equalTo("from-registry")));
    }

    /** A header whose {@code value_env} resolves nowhere fails the run by name; nothing is sent without it. */
    @Test
    void an_unresolved_header_value_env_fails_the_run_by_name() {
        String table = "ext.soap_nohdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String name = "SOAP_NO_HDR_" + System.nanoTime();
        stubFor(post(urlPathEqualTo("/eai")).willReturn(aResponse().withStatus(401)));
        String conn = connJson().replace("}", ",\"headers\":[{\"name\":\"X-Client-Id\",\"value_env\":\"" + name + "\"}]}");

        UUID id = seed(conn, "/eai", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Failed.class, s, () -> "run was: " + s);
        assertTrue(((RunStatus.Failed) s).message().contains("Header X-Client-Id unavailable (env=" + name + ")"),
            () -> "got: " + ((RunStatus.Failed) s).message());
        verify(0, postRequestedFor(urlPathEqualTo("/eai")));
    }

    /** A cancel asked while the single fetch is under way stops it between two pages: the run ends
     *  {@code Cancelled} before the third request, and nothing it read is written (#1444). */
    @Test
    void a_cancel_stops_the_fetch_between_two_pages() throws Exception {
        String table = "ext.soap_cancel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String page = envelope("<Out><ListOfCustomer>"
            + "<Customer><Id>a</Id><Name>X</Name></Customer>"
            + "<Customer><Id>b</Id><Name>Y</Name></Customer>"
            + "</ListOfCustomer><LastPage>false</LastPage></Out>");
        // Four pages, the fourth the last: without the check the whole fetch is read, then written.
        stubFor(post(urlPathEqualTo("/eai")).atPriority(5)
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(page)));
        stubFor(post(urlPathEqualTo("/eai")).atPriority(1)
            .withRequestBody(containing("<StartRowNum>6</StartRowNum>"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml")
                .withBody(page.replace("<LastPage>false</LastPage>", "<LastPage>true</LastPage>"))));
        // The second page is slow, so the cancel is asked while it is in flight.
        stubFor(post(urlPathEqualTo("/eai")).atPriority(1)
            .withRequestBody(containing("<StartRowNum>2</StartRowNum>"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(page).withFixedDelay(1500)));

        UUID id = seed(connJson(), "/eai", epJson(table));

        CompletableFuture<RunStatus> running = CompletableFuture.supplyAsync(() ->
            TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L))));
        await().atMost(Duration.ofSeconds(30)).until(() -> !wm.findAll(postRequestedFor(urlPathEqualTo("/eai"))
            .withRequestBody(containing("<StartRowNum>2</StartRowNum>"))).isEmpty());
        UUID runId = runInTxAsSystem(utx, () -> runs.findLatestActiveByConnector(id).orElseThrow().getId());
        assertTrue(engine.cancel(runId), "the run is in flight on this hub");

        RunStatus s = running.get(30, TimeUnit.SECONDS);
        assertInstanceOf(RunStatus.Cancelled.class, s, () -> "run was: " + s);
        verify(2, postRequestedFor(urlPathEqualTo("/eai")));   // pages 1 and 2, never the third
        // Conservation holds on the cancelled run, and nothing it read was written.
        ConnectorRun run = runInTxAsSystem(utx, () -> runs.findById(runId));
        assertEquals(List.of(0L, 0L, 0L), List.of(run.getRecordsIn(), run.getRecordsOut(), run.getRecordsSkipped()),
            "records_in, records_out, records_skipped");
    }

    @Test
    void soap_fault_fails_the_run() {
        String table = "ext.soap_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // 200 + a Fault body is deterministic: SoapFaultValidator runs on the 2xx and throws a
        // permanent ConnectorException immediately (no status-based retry timing to reason about).
        // The unit test (SoapFaultValidatorTest) separately covers the realistic HTTP-500 fault.
        stubFor(post(urlPathEqualTo("/eai")).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "text/xml")
            .withBody(envelope("<soap:Fault><faultcode>Server</faultcode>"
                + "<faultstring>QueryPage failed</faultstring></soap:Fault>"))));

        UUID id = seed(connJson(), "/eai", epJson(table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Failed.class, s);
        assertTrue(((RunStatus.Failed) s).message().toLowerCase().contains("fault"),
            "Expected a SOAP Fault message, got: " + ((RunStatus.Failed) s).message());
    }
}
