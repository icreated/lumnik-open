package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BodyLinkPaginationStrategyTest {

    private BodyLinkPaginationStrategy strategy() {
        return new BodyLinkPaginationStrategy(Map.of("link_path", "$.next", "response_path", "$.results"));
    }

    private RestResponse resp(String body) {
        return RestResponse.of(200, Map.of(), body);
    }

    @Test
    void first_is_a_get_to_base_plus_path() {
        var cfg = new EndpointConfig("http://api", "GET", "/v1/items", Map.of());
        RestRequest req = strategy().first(cfg, null);
        assertEquals("GET", req.method());
        assertEquals("http://api/v1/items", req.url());
    }

    @Test
    void next_follows_the_body_url_until_null() {
        RestRequest current = RestRequest.get("http://api/v1/items");
        RestRequest next = strategy().next(resp("{\"next\":\"http://api/v1/items?page=2\",\"results\":[]}"), current);
        assertNotNull(next);
        assertEquals("http://api/v1/items?page=2", next.url());

        assertNull(strategy().next(resp("{\"next\":null,\"results\":[]}"), current));
        assertNull(strategy().next(resp("{\"results\":[]}"), current));   // absent
    }

    @Test
    void next_preserves_headers_and_query_params() {
        // Connector-level RequestHeaders (e.g. an API key in spec.headers) are decorated onto the
        // FIRST request only; the strategy MUST carry them — and existing query params — onto the
        // body-provided next URL, or page 2+ silently drop them. (Auth is re-signed per page.)
        RestRequest current = RestRequest.get("http://api/v1/items")
            .withHeader("X-Api-Key", "secret")
            .withQuery("tenant", "acme");
        RestRequest next = strategy().next(
            resp("{\"next\":\"http://api/v1/items?cursor=abc\",\"results\":[]}"), current);
        assertNotNull(next);
        assertEquals("http://api/v1/items?cursor=abc", next.url());
        assertEquals("secret", next.headers().get("X-Api-Key"), "header dropped across pages");
        assertEquals("acme", next.queryParams().get("tenant"), "query param dropped across pages");
    }

    /** #1633: a next URL that echoes a query param carries it once — the server's URL is authoritative. */
    @Test
    void next_does_not_repeat_a_param_the_next_url_carries() {
        RestRequest current = RestRequest.get("http://api/v1/items").withQuery("tenant", "acme").withQuery("since", "T");
        RestRequest next = strategy().next(
            resp("{\"next\":\"http://api/v1/items?cursor=abc&since=T\",\"results\":[]}"), current);
        assertEquals(Map.of("tenant", "acme"), next.queryParams(), "since is in the next URL; tenant is not");
    }

    /** #1638: Drupal writes JSON:API links as link objects — {"href": …} — which the spec allows beside strings. */
    @Test
    void a_link_object_is_followed_by_its_href() {
        BodyLinkPaginationStrategy jsonApi = new BodyLinkPaginationStrategy(Map.of("link_path", "$.links.next", "response_path", "$.data"));
        RestRequest next = jsonApi.next(resp("{\"links\":{\"next\":{\"href\":\"http://api/v1/items?page%5Boffset%5D=10\"}},\"data\":[]}"),
            RestRequest.get("http://api/v1/items"));
        assertEquals("http://api/v1/items?page%5Boffset%5D=10", next.url());
    }

    @Test
    void a_link_object_without_href_ends_nothing_silently_and_is_refused_by_its_key() {
        BodyLinkPaginationStrategy jsonApi = new BodyLinkPaginationStrategy(Map.of("link_path", "$.links.next", "response_path", "$.data"));
        for (String link : List.of("{\"url\":\"http://api/v1/items?p=2\"}", "42")) {
            var e = assertThrows(ConnectorException.class, () -> jsonApi.next(
                resp("{\"links\":{\"next\":" + link + "},\"data\":[]}"), RestRequest.get("http://api/v1/items")));
            assertTrue(e.getMessage().startsWith("pagination.link_path must read a URL or a link object with href, not a"), e.getMessage());
            assertFalse(e.getMessage().contains("p=2") || e.getMessage().contains("42"), "never the value: " + e.getMessage());
        }
    }

    @Test
    void a_link_object_whose_href_is_absent_or_blank_is_the_last_page() {
        BodyLinkPaginationStrategy jsonApi = new BodyLinkPaginationStrategy(Map.of("link_path", "$.links.next", "response_path", "$.data"));
        assertNull(jsonApi.next(resp("{\"links\":{\"next\":{\"href\":\"\"}},\"data\":[]}"), RestRequest.get("http://api/v1/items")));
        assertNull(jsonApi.next(resp("{\"links\":{\"self\":{\"href\":\"http://api/v1/items\"}},\"data\":[]}"), RestRequest.get("http://api/v1/items")));
    }

    @Test
    void next_refuses_cloud_metadata_address_from_body() {
        // SSRF: a hostile/compromised upstream can put ANY absolute URL in the body — including the
        // cloud metadata address — and today next() would follow it. Must be refused, not returned.
        RestRequest current = RestRequest.get("http://api/v1/items");
        RestResponse resp = resp("{\"next\":\"http://169.254.169.254/latest/meta-data/\",\"results\":[]}");
        assertThrows(RuntimeException.class, () -> strategy().next(resp, current));
    }

    @Test
    void next_refuses_loopback_address_from_body() {
        RestRequest current = RestRequest.get("http://api/v1/items");
        RestResponse resp = resp("{\"next\":\"http://127.0.0.1/admin\",\"results\":[]}");
        assertThrows(RuntimeException.class, () -> strategy().next(resp, current));
    }

    @Test
    void next_refuses_a_different_host_from_body() {
        RestRequest current = RestRequest.get("http://api/v1/items");
        RestResponse resp = resp("{\"next\":\"http://evil.example/steal\",\"results\":[]}");
        assertThrows(RuntimeException.class, () -> strategy().next(resp, current));
    }

    @Test
    void rejects_missing_link_path_or_response_path() {
        // Fail fast at construction (like page_param on PagePaginationStrategy) — a misconfigured
        // body-link would otherwise silently stop paging / return empty items.
        assertThrows(IllegalArgumentException.class,
            () -> new BodyLinkPaginationStrategy(Map.of("response_path", "$.results")));   // no link_path
        assertThrows(IllegalArgumentException.class,
            () -> new BodyLinkPaginationStrategy(Map.of("link_path", "$.next")));          // no response_path
    }

    @Test
    void items_reads_response_path() {
        List<Map<String, Object>> items =
            strategy().items(resp("{\"results\":[{\"id\":1},{\"id\":2}]}"));
        assertEquals(2, items.size());
    }
}
