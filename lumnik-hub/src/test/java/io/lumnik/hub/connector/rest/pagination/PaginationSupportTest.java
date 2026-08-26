package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaginationSupportTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void items_reads_the_list_at_response_path() throws Exception {
        var json = M.readTree("{\"data\":[{\"id\":1},{\"id\":2}]}");
        List<Map<String, Object>> items = PaginationSupport.items(json, "$.data");
        assertEquals(2, items.size());
    }

    @Test
    void items_is_empty_when_path_absent_or_not_a_list() throws Exception {
        assertTrue(PaginationSupport.items(M.readTree("{\"data\":{\"id\":1}}"), "$.data").isEmpty());
        assertTrue(PaginationSupport.items(M.readTree("{}"), "$.missing").isEmpty());
    }

    @Test
    void baseGet_joins_base_and_path_with_one_slash() {
        var cfg = new EndpointConfig("http://api", "GET", "items", Map.of());   // no leading slash
        RestRequest req = PaginationSupport.baseGet(cfg);
        assertEquals("GET", req.method());
        assertEquals("http://api/items", req.url());
    }

    @Test
    void baseGet_returns_sentinel_for_null_cfg() {
        assertEquals("about:blank", PaginationSupport.baseGet(null).url());
    }
}
