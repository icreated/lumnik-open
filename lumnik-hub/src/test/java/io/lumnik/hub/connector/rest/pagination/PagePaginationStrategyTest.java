package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
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
        var c = new HashMap<String, String>();
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
        var c = new HashMap<String, String>();
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

    /** #1611: a flag that reads no boolean is refused by its key. */
    @Test void a_has_more_path_that_reads_no_boolean_is_refused_by_its_key() {
        PagePaginationStrategy p = new PagePaginationStrategy(Map.of(
            "page_param", "page", "has_more_path", "$.more", "response_path", "$.content"));
        RestResponse r = RestResponse.of(200, Map.of(), "{\"more\":\"yes\",\"content\":[{}]}");
        ConnectorException e = assertThrows(
            ConnectorException.class, () -> p.next(r, p.first(null, null)));
        assertEquals("pagination.has_more_path must read a boolean, not a string", e.getMessage());
    }

    /** code-review of #1611: a blank total_pages_path is none, and a total sent as text still counts. */
    @Test void a_blank_total_pages_path_is_none_and_a_text_total_counts() {
        PagePaginationStrategy blank = new PagePaginationStrategy(Map.of(
            "page_param", "page", "total_pages_path", "", "response_path", "$.content"));
        RestResponse page = RestResponse.of(200, Map.of(), "{\"content\":[{}]}");
        assertEquals("2", blank.next(page, blank.first(null, null)).queryParams().get("page"));

        PagePaginationStrategy text = new PagePaginationStrategy(Map.of(
            "page_param", "page", "total_pages_path", "$.pages", "response_path", "$.content"));
        assertNull(text.next(RestResponse.of(200, Map.of(), "{\"pages\":\"1\",\"content\":[{}]}"), text.first(null, null)));
    }

    /** #1800: Micronaut 4 sends no page count, only the list's total; the rows read so far say when the list is done. */
    private static PagePaginationStrategy withTotalOnly(boolean sizeParam) {
        var c = new HashMap<String, String>();
        c.put("page_param", "page");
        if (sizeParam) c.put("size_param", "size");
        c.put("size", "50");
        c.put("start_page", "0");
        c.put("total_path", "$.totalSize");
        c.put("response_path", "$.content");
        return new PagePaginationStrategy(c);
    }

    private static String page(int rows) {
        return "{\"content\":[" + "{},".repeat(rows - 1) + "{}],\"totalSize\":130}";
    }

    @Test void a_total_and_no_page_count_stops_once_the_rows_read_reach_it() {
        var s = withTotalOnly(true);
        assertNotNull(s.next(RestResponse.of(200, Map.of(), page(50)), RestRequest.get("http://api").withQuery("page", "0")));
        assertNotNull(s.next(RestResponse.of(200, Map.of(), page(50)), RestRequest.get("http://api").withQuery("page", "1")));
        assertNull(s.next(RestResponse.of(200, Map.of(), page(30)), RestRequest.get("http://api").withQuery("page", "2")),
            "50 + 50 + 30 = 130 rows read: no empty page 3 to ask for");
    }

    @Test void a_page_size_the_api_chooses_is_not_counted_against_the_total() {
        // Without size_param the page size is the API's, not the configured 50: rows read cannot be inferred.
        var s = withTotalOnly(false);
        assertNotNull(s.next(RestResponse.of(200, Map.of(), page(30)), RestRequest.get("http://api").withQuery("page", "2")));
    }
}
