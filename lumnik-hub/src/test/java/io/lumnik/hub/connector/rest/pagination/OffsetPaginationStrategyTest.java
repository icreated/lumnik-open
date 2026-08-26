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
}
