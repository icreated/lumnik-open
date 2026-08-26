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

/** End-to-end SOAP: POST envelope, offset paging (StartRowNum), LastPage stop, force-list, Fault->fail. */
@QuarkusTest
class SoapConnectorIT {

    @Inject ConnectorEngine engine;
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

    private String epJson(String table) {
        String body = "<In><PageSize>{{page_size}}</PageSize><StartRowNum>{{cursor}}</StartRowNum></In>";
        return "{\"kind\":\"soap\",\"soap_action\":\"CustomerQueryPage\","
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
