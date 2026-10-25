package io.lumnik.hub.connector.rest.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SoapPaginationStrategyTest {

    private static final ObjectMapper M = new ObjectMapper();

    private SoapPaginationStrategy strat() {
        return new SoapPaginationStrategy(Map.of(
            "kind", "soap",
            "page_size", "2",
            "last_page_path", "$.Body.Out.LastPage",
            "response_path", "$.Body.Out.ListOfCustomer.Customer"));
    }

    private EndpointConfig cfg() {
        return new EndpointConfig("http://h", "POST", "/svc", Map.of(
            "body", "<In><PageSize>{{page_size}}</PageSize><StartRowNum>{{cursor}}</StartRowNum></In>",
            "soap_action", "A"));
    }

    /** Build a converted (namespace-stripped) response tree the way XmlBodyParser would. */
    private RestResponse converted(boolean lastPage) throws Exception {
        String json = "{\"Body\":{\"Out\":{\"LastPage\":" + lastPage + ",\"ListOfCustomer\":{\"Customer\":[]}}}}";
        return new RestResponse(200, Map.of(), "", M.readTree(json));
    }

    @Test
    void first_seeds_cursor_at_zero() {
        RestRequest r = strat().first(cfg(), null);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.body()).contains("<StartRowNum>0</StartRowNum>");
        assertThat(r.body()).contains("<PageSize>2</PageSize>");
    }

    @Test
    void next_advances_the_offset_by_page_size_while_not_last_page() throws Exception {
        SoapPaginationStrategy s = strat();
        s.first(cfg(), null);                       // seed offset = 0
        RestRequest next = s.next(converted(false), null);
        assertThat(next).isNotNull();
        assertThat(next.body()).contains("<StartRowNum>2</StartRowNum>");
    }

    @Test
    void next_advances_the_page_number_while_not_last_page() throws Exception {
        SoapPaginationStrategy s = strat();
        RestRequest first = s.first(cfgWithBody("<In><page>{{page}}</page></In>"), null);
        assertThat(first.body()).contains("<page>1</page>");
        assertThat(s.next(converted(false), null).body()).contains("<page>2</page>");
    }

    private EndpointConfig cfgWithBody(String body) {
        return new EndpointConfig("http://h", "POST", "/svc", Map.of("body", body, "soap_action", "A"));
    }

    @Test
    void next_stops_on_the_last_page() throws Exception {
        SoapPaginationStrategy s = strat();
        s.first(cfg(), null);
        assertThat(s.next(converted(true), null)).isNull();
    }

    @Test
    void next_handles_string_typed_booleans_from_xml() throws Exception {
        // XmlMapper yields text nodes — <LastPage>false</LastPage> becomes the STRING "false".
        // A naive Boolean.equals check would (wrongly) stop after page 1; we must treat it as falsey.
        SoapPaginationStrategy s = strat();
        s.first(cfg(), null);
        String json = "{\"Body\":{\"Out\":{\"LastPage\":\"false\",\"ListOfCustomer\":{\"Customer\":[]}}}}";
        RestResponse stringBool = new RestResponse(200, Map.of(), "", M.readTree(json));
        assertThat(s.next(stringBool, null)).isNotNull();      // "false" => not last page => more
        String last = "{\"Body\":{\"Out\":{\"LastPage\":\"true\",\"ListOfCustomer\":{\"Customer\":[]}}}}";
        SoapPaginationStrategy s2 = strat();
        s2.first(cfg(), null);
        assertThat(s2.next(new RestResponse(200, Map.of(), "", M.readTree(last)), null)).isNull();
    }

    @Test
    void non_numeric_page_size_fails_fast() {
        assertThatThrownBy(() -> new SoapPaginationStrategy(Map.of(
                "kind", "soap", "page_size", "abc", "response_path", "$.Body.Out.Items")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zero_page_size_fails_fast() {
        assertThatThrownBy(() -> new SoapPaginationStrategy(Map.of(
                "kind", "soap", "page_size", "0", "response_path", "$.Body.Out.Items")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blank_last_page_path_is_treated_as_absent() throws Exception {
        // An empty string from YAML must not reach JsonPathExtractor.read("") — that throws.
        SoapPaginationStrategy s = new SoapPaginationStrategy(Map.of(
            "kind", "soap", "page_size", "2", "response_path", "$.Body.Out.Items",
            "last_page_path", ""));   // blank = absent → fall back to short-page stop
        s.first(cfg(), null);
        String json = "{\"Body\":{\"Out\":{\"Items\":[]}}}";
        RestResponse empty = new RestResponse(200, Map.of(), "", M.readTree(json));
        assertThat(s.next(empty, null)).isNull();  // 0 items < pageSize=2 → short-page stop
    }

    @Test
    void next_carries_connector_headers_from_current() throws Exception {
        SoapPaginationStrategy s = strat();
        s.first(cfg(), null);
        RestRequest current = new RestRequest("POST", "http://h/svc",
            Map.of("X-Api-Version", "2", "X-Tenant", "acme"), Map.of(), "");
        RestRequest next = s.next(converted(false), current);
        assertThat(next).isNotNull();
        // connector-level headers are carried forward to subsequent pages
        assertThat(next.headers()).containsEntry("X-Api-Version", "2");
        assertThat(next.headers()).containsEntry("X-Tenant", "acme");
        // SOAP-specific headers (Content-Type, SOAPAction) still present and override base
        assertThat(next.headers()).containsKey("Content-Type");
        assertThat(next.headers()).containsKey("SOAPAction");
    }

    @Test
    void next_carries_query_params_from_current() throws Exception {
        SoapPaginationStrategy s = strat();
        s.first(cfg(), null);
        RestRequest current = new RestRequest("POST", "http://h/svc",
            Map.of(), Map.of("watermark", "2026-01-01"), "");
        RestRequest next = s.next(converted(false), current);
        assertThat(next).isNotNull();
        assertThat(next.queryParams()).containsEntry("watermark", "2026-01-01");
    }

    @Test
    void env_placeholder_falls_back_to_system_property_for_dev_injection() throws Exception {
        // EnvSecrets.lookup() tries System.getenv first, then System.getProperty —
        // allows injecting secrets via -Dname=value in dev/test without setting OS env vars.
        // Highly specific key — extremely unlikely to exist as a real OS env var in CI.
        String key = "LUMNIK_SOAP_TEST_ENV_FALLBACK";
        System.setProperty(key, "tok123");
        try {
            SoapPaginationStrategy s = new SoapPaginationStrategy(Map.of(
                "kind", "soap", "page_size", "10", "response_path", "$.Body.Out.Items"));
            EndpointConfig ec = new EndpointConfig("http://h", "POST", "/svc", Map.of(
                "body", "<In><Token>{{env:" + key + "}}</Token><Num>{{cursor}}</Num></In>",
                "soap_action", "A"));
            RestRequest r = s.first(ec, null);
            assertThat(r.body()).contains("<Token>tok123</Token>");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void items_reads_the_response_path() throws Exception {
        String json = "{\"Body\":{\"Out\":{\"ListOfCustomer\":{\"Customer\":"
            + "[{\"Id\":\"a\"},{\"Id\":\"b\"}]}}}}";
        RestResponse resp = new RestResponse(200, Map.of(), "", M.readTree(json));
        assertThat(strat().items(resp)).hasSize(2);
    }
}
