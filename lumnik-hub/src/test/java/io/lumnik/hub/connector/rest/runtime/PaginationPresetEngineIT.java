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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Preset expansion through the REAL apply front door, then ingestion via the engine.
 * Proves: manifest pagination.kind="drf-page" expands to body-link and follows $.next;
 *          "spring-hateoas" follows $._links.next.href, its items keyed by their self link's last segment (#1630);
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

    private List<String> keys(String table) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                @SuppressWarnings("unchecked")
                List<String> keys = em.createNativeQuery("SELECT external_id FROM " + table).getResultList();
                return keys;
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

    /** #1827: SAP Gateway — the answer under {@code d}, the next page in {@code d.__next}, the total as text. */
    @Test
    void odata_v2_preset_paginates_and_reads_its_text_total() {
        String table = "ext.preset_odata2_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();

        stubFor(get(urlPathEqualTo("/odata/items"))
            .withQueryParam("$skiptoken", absent())
            .willReturn(okJson("{\"d\":{\"__count\":\"3\",\"__next\":\"" + base + "/odata/items?$skiptoken=2\","
                + "\"results\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}}")));
        stubFor(get(urlPathEqualTo("/odata/items"))
            .withQueryParam("$skiptoken", equalTo("2"))
            .willReturn(okJson("{\"d\":{\"__count\":\"3\",\"results\":[{\"id\":\"c\",\"name\":\"Z\"}]}}")));

        UUID id = apply(odataManifest("odata-v2", "odata2", base, table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
    }

    /** #1827: OData v4 — members named {@code @odata.nextLink} / {@code @odata.count}, read through bracketed paths. */
    @Test
    void odata_v4_preset_paginates_and_reads_its_count() {
        String table = "ext.preset_odata4_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();

        stubFor(get(urlPathEqualTo("/odata/items"))
            .withQueryParam("$skiptoken", absent())
            .willReturn(okJson("{\"@odata.count\":3,\"@odata.nextLink\":\"" + base + "/odata/items?$skiptoken=2\","
                + "\"value\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo("/odata/items"))
            .withQueryParam("$skiptoken", equalTo("2"))
            .willReturn(okJson("{\"@odata.count\":3,\"value\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID id = apply(odataManifest("odata-v4", "odata4", base, table));

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
    }

    private static String odataManifest(String preset, String name, String base, String table) {
        return "{\"metadata\":{\"name\":\"" + name + "-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"items\",\"path\":\"/odata/items\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"" + preset + "\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}]}}";
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

    /** #1642: under VIA_DTO, Spring writes a Page as PagedModel — the total under "page", read without an empty page. */
    @Test
    void spring_paged_model_preset_stops_on_the_total_under_page() {
        String table = "ext.preset_paged_model_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/api/models")).withQueryParam("page", equalTo("0"))
            .willReturn(okJson("{\"content\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}],\"page\":{\"size\":2,\"number\":0,\"totalElements\":3,\"totalPages\":2}}")));
        stubFor(get(urlPathEqualTo("/api/models")).withQueryParam("page", equalTo("1"))
            .willReturn(okJson("{\"content\":[{\"id\":\"c\",\"name\":\"Z\"}],\"page\":{\"size\":2,\"number\":1,\"totalElements\":3,\"totalPages\":2}}")));
        stubFor(get(urlPathEqualTo("/api/models")).withQueryParam("page", equalTo("2"))
            .willReturn(okJson("{\"content\":[],\"page\":{\"size\":2,\"number\":2,\"totalElements\":3,\"totalPages\":2}}")));

        UUID id = apply("{\"metadata\":{\"name\":\"paged-model-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"models\",\"path\":\"/api/models\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"spring-paged-model\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(0, getRequestedFor(urlPathEqualTo("/api/models")).withQueryParam("page", equalTo("2")));
    }

    /** #1650: Spring PetClinic of before Boot answers its whole list at once — one request, every row. */
    @Test
    void none_reads_the_whole_list_in_one_request() {
        String table = "ext.none_vets_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/petclinic/vets.json"))
            .willReturn(okJson("{\"vetList\":[{\"id\":1,\"lastName\":\"Carter\"},{\"id\":3,\"lastName\":\"Douglas\"},"
                + "{\"id\":6,\"lastName\":\"Jenkins\"}]}")));

        UUID id = apply("{\"metadata\":{\"name\":\"none-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "/petclinic\",\"endpoints\":[{"
            + "\"id\":\"vets\",\"path\":\"/vets.json\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"none\",\"response_path\":\"$.vetList\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"last_name\",\"path\":\"$.lastName\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(1, getRequestedFor(urlPathEqualTo("/petclinic/vets.json")));
    }

    /** #1754: PrestaShop's Webservice ignores offset=; its limit carries both, limit=offset,count. */
    @Test
    void prestashop_pages_with_offset_and_count_in_limit() {
        String table = "ext.preset_prestashop_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("limit", equalTo("0,2"))
            .willReturn(okJson("{\"products\":[{\"id\":1,\"reference\":\"demo_1\"},{\"id\":2,\"reference\":\"demo_3\"}]}")));
        stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("limit", equalTo("2,2"))
            .willReturn(okJson("{\"products\":[{\"id\":3,\"reference\":\"demo_6\"}]}")));

        UUID id = apply("{\"metadata\":{\"name\":\"ps-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "/api\",\"endpoints\":[{"
            + "\"id\":\"products\",\"path\":\"/products?output_format=JSON\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"prestashop\",\"resource\":\"products\",\"limit\":2},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"reference\",\"path\":\"$.reference\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(3L, count(table));
        verify(2, getRequestedFor(urlPathEqualTo("/api/products")).withQueryParam("output_format", equalTo("JSON")));
        verify(0, getRequestedFor(urlPathEqualTo("/api/products")).withQueryParam("offset", matching(".*")));
    }

    /** #1755: PrestaShop's Webservice as it answers, in XML — response_format: xml, the preset's pagination, attributes under @. */
    @Test
    void prestashop_xml_is_read_with_response_format_xml() {
        String table = "ext.preset_prestashop_xml_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("limit", equalTo("0,2"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml;charset=utf-8").withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><prestashop xmlns:xlink=\"http://www.w3.org/1999/xlink\"><products>"
                    + psProduct(1, "demo_1", "Hummingbird printed t-shirt") + psProduct(2, "demo_3", "Hummingbird printed sweater")
                    + "</products></prestashop>")));
        stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("limit", equalTo("2,2"))
            .willReturn(aResponse().withHeader("Content-Type", "text/xml;charset=utf-8").withBody(
                "<prestashop xmlns:xlink=\"http://www.w3.org/1999/xlink\"><products>" + psProduct(3, "demo_6", "Mug The best is yet to come")
                    + "</products></prestashop>")));

        UUID id = apply("{\"metadata\":{\"name\":\"psx-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "/api\",\"endpoints\":[{"
            + "\"id\":\"products\",\"path\":\"/products?display=full\",\"method\":\"GET\",\"response_format\":\"xml\","
            + "\"pagination\":{\"kind\":\"prestashop\",\"resource\":\"products\",\"limit\":2,\"response_path\":\"$.products.product\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
            + "\"promote\":[{\"name\":\"reference\",\"path\":\"$.reference\",\"type\":\"text\"},"
            + "{\"name\":\"name\",\"path\":\"$.name.language['#text']\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "run was: " + s);
        assertEquals(3L, count(table));
        assertEquals("Mug The best is yet to come", TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                return (String) em.createNativeQuery("SELECT name FROM " + table + " WHERE external_id = '3'").getSingleResult();
            } finally {
                utx.commit();
            }
        }));
    }

    private static String psProduct(int id, String reference, String name) {
        return "<product><id><![CDATA[" + id + "]]></id><reference><![CDATA[" + reference + "]]></reference>"
            + "<name><language id=\"1\" xlink:href=\"http://shop.test/api/languages/1\"><![CDATA[" + name + "]]></language></name></product>";
    }

    /** #1630: Spring Data REST names an item only by its self link; the key is its last segment, not the host. */
    @Test
    void spring_hateoas_items_are_keyed_by_their_self_link() {
        String table = "ext.preset_hateoas_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/products")).withQueryParam("page", absent())
            .willReturn(okJson("{\"_embedded\":{\"products\":[" + product(base, "SKU-1") + "," + product(base, "SKU%2F2") + "]},"
                + "\"_links\":{\"next\":{\"href\":\"" + base + "/products?page=1&size=2\"}},\"page\":{\"totalElements\":3}}")));
        stubFor(get(urlPathEqualTo("/products")).withQueryParam("page", equalTo("1"))
            .willReturn(okJson("{\"_embedded\":{\"products\":[" + product(base, "SKU-3") + "]},"
                + "\"_links\":{},\"page\":{\"totalElements\":3}}")));

        UUID id = apply("{\"metadata\":{\"name\":\"hateoas-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"products\",\"path\":\"/products\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"spring-hateoas\",\"items\":\"products\",\"total_path\":\"$.page.totalElements\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\","
            + "\"unique_key\":{\"path\":\"$._links.self.href\",\"transform\":\"last_path_segment\"},"
            + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        assertEquals(Set.of("SKU-1", "SKU/2", "SKU-3"), Set.copyOf(keys(table)));
    }

    /** #1630: a self link with no segment reads no key — the run fails, never a row under an empty key. */
    @Test
    void an_item_whose_link_reads_no_key_fails_the_run() {
        String table = "ext.preset_hateoas_nokey_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/products")).willReturn(okJson("{\"_embedded\":{\"products\":["
            + product(base, "SKU-1") + ",{\"name\":\"root\",\"_links\":{\"self\":{\"href\":\"" + base + "/\"}}}]},\"_links\":{}}")));

        UUID id = apply("{\"metadata\":{\"name\":\"hateoas-nokey-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"products\",\"path\":\"/products\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"spring-hateoas\",\"items\":\"products\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\","
            + "\"unique_key\":{\"path\":\"$._links.self.href\",\"transform\":\"last_path_segment\"},\"promote\":[]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Failed.class, s, () -> "got " + s);
        assertTrue(((RunStatus.Failed) s).message().contains("unique_key '$._links.self.href' reads no value"),
            () -> "named by the key's path, got: " + s);
        assertEquals(0L, count(table), "the page's batch is not written");
    }

    private static String product(String base, String sku) {
        return "{\"name\":\"" + sku + "\",\"_links\":{\"self\":{\"href\":\"" + base + "/products/" + sku + "\"}}}";
    }

    /** #1632: a link-header API publishing its count in X-Total-Count — the witness holds the run to it. */
    @Test
    void a_count_in_a_response_header_is_the_runs_witness() {
        String table = "ext.total_header_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/issues")).withQueryParam("page", absent())
            .willReturn(okJson("[{\"id\":\"a\"}]").withHeader("X-Total-Count", "3")
                .withHeader("Link", "<" + base + "/issues?page=2>; rel=\"next\"")));
        stubFor(get(urlPathEqualTo("/issues")).withQueryParam("page", equalTo("2"))
            .willReturn(okJson("[{\"id\":\"b\"}]").withHeader("X-Total-Count", "3")));

        UUID id = apply("{\"metadata\":{\"name\":\"total-header-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "\",\"endpoints\":[{"
            + "\"id\":\"issues\",\"path\":\"/issues\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"link-header\",\"response_path\":\"$\",\"total_header\":\"X-Total-Count\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\",\"promote\":[]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Failed.class, s, () -> "got " + s);
        assertTrue(((RunStatus.Failed) s).message().contains("declared 3 rows but this run returned 2"), () -> "got " + s);
    }

    /** #1638: Drupal's JSON:API writes links as link objects — the json-api preset follows their href. */
    @Test
    void json_api_preset_follows_a_link_object_as_drupal_writes_it() {
        String table = "ext.preset_jsonapi_" + Long.toUnsignedString(System.nanoTime());
        String base = "http://localhost:" + wm.port();
        stubFor(get(urlPathEqualTo("/jsonapi/node/article")).withQueryParam("page[offset]", absent())
            .willReturn(okJson("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"links\":{\"next\":{\"href\":\""
                + base + "/jsonapi/node/article?page%5Boffset%5D=2\"},\"self\":{\"href\":\"" + base + "/jsonapi/node/article\"}}}")));
        stubFor(get(urlPathEqualTo("/jsonapi/node/article")).withQueryParam("page[offset]", equalTo("2"))
            .willReturn(okJson("{\"data\":[{\"id\":\"c\"}],\"links\":{\"self\":{\"href\":\"" + base + "/jsonapi/node/article\"}}}")));

        UUID id = apply("{\"metadata\":{\"name\":\"jsonapi-" + Long.toUnsignedString(System.nanoTime()) + "\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"" + base + "/jsonapi\",\"endpoints\":[{"
            + "\"id\":\"articles\",\"path\":\"/node/article\",\"method\":\"GET\","
            + "\"pagination\":{\"kind\":\"json-api\"},"
            + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\",\"promote\":[]}}]}}");

        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s, () -> "got " + s);
        assertEquals(3L, count(table));
    }
}
