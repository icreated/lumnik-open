package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OffsetPaginationStrategyTest {
    OffsetPaginationStrategy s = new OffsetPaginationStrategy(Map.of(
        "offset_param", "offset", "limit_param", "limit", "limit", "10", "response_path", "$.items"));
    @Test void increments_offset() {
        RestRequest first = s.first(null, null);
        assertEquals("0", first.queryParams().get("offset"));
        RestResponse r = RestResponse.of(200, Map.of(),
            "{\"items\":[{},{},{},{},{},{},{},{},{},{}]}");
        RestRequest next = s.next(r, first);
        assertEquals("10", next.queryParams().get("offset"));
    }
    @Test void stops_when_items_lt_limit() {
        RestRequest req = RestRequest.get("https://x").withQuery("offset", "0");
        RestResponse r = RestResponse.of(200, Map.of(), "{\"items\":[{},{}]}");
        assertNull(s.next(r, req));
    }

    /** #1754: PrestaShop's Webservice pages with one parameter carrying both, limit=offset,count — named twice, written once. */
    OffsetPaginationStrategy oneParam = new OffsetPaginationStrategy(Map.of(
        "offset_param", "limit", "limit_param", "limit", "limit", "50", "response_path", "$.products"));

    private static RestResponse rows(int n) {
        return RestResponse.of(200, Map.of(), "{\"products\":[" + String.join(",", java.util.Collections.nCopies(n, "{}")) + "]}");
    }

    @Test void one_parameter_named_twice_carries_offset_and_count() {
        RestRequest first = oneParam.first(new EndpointConfig("https://shop.test/api", "GET", "/products", Map.of()), null);
        assertEquals(Map.of("limit", "0,50"), first.queryParams());
        RestRequest second = oneParam.next(rows(50), first);
        assertEquals(Map.of("limit", "50,50"), second.queryParams());
        assertEquals(Map.of("limit", "100,50"), oneParam.next(rows(50), second).queryParams());
    }

    @Test void one_parameter_stops_on_a_short_page() {
        assertNull(oneParam.next(rows(19), RestRequest.get("https://shop.test/api/products").withQuery("limit", "0,50")));
    }
}
