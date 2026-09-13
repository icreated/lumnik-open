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
    void declaredItems_reads_the_row_count_at_total_path() throws Exception {
        var json = M.readTree("{\"_embedded\":{\"partners\":[{\"id\":1}]},\"page\":{\"totalElements\":19009}}");
        assertEquals(19009L, PaginationSupport.declaredItems(json, "$.page.totalElements").getAsLong());
    }

    @Test
    void declaredItems_is_empty_when_no_path_is_named_or_the_body_carries_none() throws Exception {
        assertTrue(PaginationSupport.declaredItems(M.readTree("{\"count\":7}"), null).isEmpty(),
            "a manifest that names no total_path claims nothing");
        assertTrue(PaginationSupport.declaredItems(M.readTree("{\"results\":[]}"), "$.count").isEmpty(),
            "an absent count must not read as 0 — that would refuse every run it touches");
    }

    @Test
    void declaredItems_treats_a_blank_total_path_as_unset_rather_than_compiling_it() throws Exception {
        // `total_path: ""` is what a YAML default or a templated manifest leaves behind. It means
        // "no total was named", exactly like the absent key — not "compile the empty expression",
        // which would fail every run of an endpoint whose config is merely empty.
        assertTrue(PaginationSupport.declaredItems(M.readTree("{\"count\":7}"), "").isEmpty());
        assertTrue(PaginationSupport.declaredItems(M.readTree("{\"count\":7}"), "   ").isEmpty());
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
