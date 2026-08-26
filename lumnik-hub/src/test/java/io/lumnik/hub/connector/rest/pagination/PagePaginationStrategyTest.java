package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PagePaginationStrategyTest {
    PagePaginationStrategy s = new PagePaginationStrategy(Map.of(
        "page_param", "page", "size_param", "pageSize", "size", "50", "start_page", "1",
        "total_pages_path", "$.totalPages", "response_path", "$.content"));
    @Test void increments_page() {
        RestRequest first = s.first(null, null);
        assertEquals("1",  first.queryParams().get("page"));
        assertEquals("50", first.queryParams().get("pageSize"));
        RestResponse r = RestResponse.of(200, Map.of(), "{\"totalPages\":3,\"content\":[{}]}");
        RestRequest second = s.next(r, first);
        assertEquals("2", second.queryParams().get("page"));
    }
    @Test void stops_when_page_exceeds_total() {
        RestRequest req = RestRequest.get("https://x").withQuery("page", "3");
        RestResponse r = RestResponse.of(200, Map.of(), "{\"totalPages\":3,\"content\":[{}]}");
        assertNull(s.next(r, req));
    }

    @Test void missing_page_param_fails_fast() {
        // page_param is required for page pagination — omitting it would leave the strategy broken and
        // crash later in next(). Fail fast at construction with a clear message instead.
        assertThrows(IllegalArgumentException.class,
            () -> new PagePaginationStrategy(Map.of("response_path", "$.content")));
    }

    @Test void blank_size_param_is_treated_as_absent() {
        // size_param: "" must not emit a query parameter with an empty name
        PagePaginationStrategy blankSize = new PagePaginationStrategy(
            Map.of("page_param", "page", "size_param", "", "response_path", "$.content"));

        RestRequest first = blankSize.first(null, null);

        assertEquals(Map.of("page", "1"), first.queryParams());
    }

    @Test void first_without_size_param_does_not_npe() {
        // size_param omitted (some APIs have a fixed page size). first() must not call
        // withQuery(null, …) — Map.copyOf rejects a null key with an NPE.
        PagePaginationStrategy noSize = new PagePaginationStrategy(
            Map.of("page_param", "page", "response_path", "$.content"));

        RestRequest first = assertDoesNotThrow(() -> noSize.first(null, null));

        // only the page param is set — no size, no null key
        assertEquals(Map.of("page", "1"), first.queryParams());
    }

    @Test
    void total_pages_no_overfetch_when_zero_indexed() {
        // 0-indexed (start_page=0), totalPages=2 → valid pages are 0 and 1; the loop must stop after
        // page 1 (pages-fetched == totalPages), not fetch a non-existent page 2.
        var c = new java.util.HashMap<String, String>();
        c.put("page_param", "page");
        c.put("start_page", "0");
        c.put("total_pages_path", "$.totalPages");
        c.put("response_path", "$.content");
        var z = new PagePaginationStrategy(c);

        RestResponse r = RestResponse.of(200, Map.of(), "{\"totalPages\":2,\"content\":[{\"id\":1}]}");
        assertNotNull(z.next(r, RestRequest.get("http://api").withQuery("page", "0")));  // after page 0 → page 1
        assertNull(z.next(r, RestRequest.get("http://api").withQuery("page", "1")));     // after page 1 → STOP
    }

    @Test
    void last_path_stops_when_true() {
        var c = new java.util.HashMap<String, String>();
        c.put("page_param", "page");
        c.put("start_page", "0");
        c.put("last_path", "$.last");
        c.put("response_path", "$.content");
        var s = new PagePaginationStrategy(c);

        RestRequest cur = RestRequest.get("http://api/items").withQuery("page", "0");
        RestResponse notLast = RestResponse.of(200, Map.of(), "{\"last\":false,\"content\":[{\"id\":1}]}");
        RestResponse last    = RestResponse.of(200, Map.of(), "{\"last\":true,\"content\":[{\"id\":2}]}");

        assertNotNull(s.next(notLast, cur));   // more pages
        assertNull(s.next(last, cur));         // last page → stop
    }
}
