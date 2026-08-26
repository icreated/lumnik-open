package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CursorPaginationStrategyTest {
    CursorPaginationStrategy s = new CursorPaginationStrategy(Map.of(
        "cursor_param", "starting_after",
        "cursor_path", "$.data[-1:].id",
        "has_more_path", "$.has_more",
        "response_path", "$.data"));

    @Test void returns_null_when_no_more() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"has_more\":false,\"data\":[{\"id\":\"a\"}]}");
        assertNull(s.next(r, RestRequest.get("https://x")));
    }
    @Test void builds_next_with_cursor() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"has_more\":true,\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        RestRequest next = s.next(r, RestRequest.get("https://x"));
        assertEquals("b", next.queryParams().get("starting_after"));
    }
    @Test void extracts_items() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        assertEquals(2, s.items(r).size());
    }
}
