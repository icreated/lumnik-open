package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

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

    // ── code-review of #1611: one value per path — a list counts only when it reads at most one ──

    @Test
    void next_reads_one_value_and_an_empty_slice_is_the_end() throws Exception {
        assertEquals("b", PaginationSupport.next(M.readTree("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"), "$.data[-1:].id", "cursor_path"));
        assertNull(PaginationSupport.next(M.readTree("{\"data\":[]}"), "$.data[-1:].id", "cursor_path"));
        assertNull(PaginationSupport.next(M.readTree("{\"next\":\"  \"}"), "$.next", "link_path"));
    }

    /** A deep scan reads every connection's cursor: picking one would follow a nested one past the end. */
    @Test
    void next_refuses_a_path_that_reads_several_values() throws Exception {
        var json = M.readTree("{\"pageInfo\":{\"endCursor\":null},\"nodes\":[{\"comments\":{\"pageInfo\":{\"endCursor\":\"Yz:9\"}}}]}");
        var e = assertThrows(ConnectorException.class, () -> PaginationSupport.next(json, "$..endCursor", "cursor_path"));
        assertTrue(e.getMessage().startsWith("pagination.cursor_path must read one value, not "), e.getMessage());
        assertFalse(e.getMessage().contains("Yz:9"));
    }

    @Test
    void flag_unwraps_a_one_value_list_like_next() throws Exception {
        assertEquals(Boolean.TRUE, PaginationSupport.flag(M.readTree("{\"pages\":[{\"more\":true}]}"), "$.pages[-1:].more", "has_more_path"));
        assertNull(PaginationSupport.flag(M.readTree("{\"a\":{}}"), "$.a[?(@.more)].more", "has_more_path"));
    }

    @Test
    void number_reads_a_count_or_its_digits_and_refuses_anything_else_by_its_key() throws Exception {
        assertEquals(1234L, PaginationSupport.number(M.readTree("{\"total\":1234}"), "$.total", "total_path"));
        assertEquals(1234L, PaginationSupport.number(M.readTree("{\"total\":\"1234\"}"), "$.total", "total_path"));
        assertNull(PaginationSupport.number(M.readTree("{}"), "$.total", "total_path"));
        var e = assertThrows(ConnectorException.class,
            () -> PaginationSupport.number(M.readTree("{\"total\":\"s3cr3t\"}"), "$.total", "total_path"));
        assertEquals("pagination.total_path must read a number, not a string", e.getMessage());
        assertEquals(OptionalLong.of(1234), PaginationSupport.declaredItems(M.readTree("{\"total\":\"1234\"}"), "$.total"));
    }

    /** #1632: Gitea, GitLab, WordPress publish the count in a response header; total_header reads it. */
    @Test
    void declaredItems_reads_the_count_from_the_header_total_header_names() {
        RestResponse page = RestResponse.of(200, Map.of("x-total-count", List.of("72")), "[{}]");
        assertEquals(OptionalLong.of(72), PaginationSupport.declaredItems(page, null, "X-Total-Count"), "a header is named in any case");
        assertEquals(OptionalLong.of(72), PaginationSupport.declaredItems(
            RestResponse.of(200, Map.of("X-Total-Count", List.of(" 72 ")), "[{}]"), null, "X-Total-Count"));
    }

    @Test
    void declaredItems_from_a_header_is_empty_when_the_response_carries_none() {
        assertTrue(PaginationSupport.declaredItems(RestResponse.of(200, Map.of(), "[{}]"), null, "X-Total-Count").isEmpty());
    }

    /** A row count is a whole number from 0: text and a negative are refused by the key, never the value (Copilot on #1637). */
    @ParameterizedTest
    @ValueSource(strings = {"seventy-two", "-1", "7.5", "1e3"})
    void declaredItems_from_a_header_refuses_what_is_not_a_row_count_by_its_key_never_its_value(String value) {
        RestResponse page = RestResponse.of(200, Map.of("X-Total-Count", List.of(value)), "[{}]");
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> PaginationSupport.declaredItems(page, null, "X-Total-Count"));
        assertEquals(ROW_COUNT_REFUSAL, e.getMessage());
    }

    private static final String ROW_COUNT_REFUSAL =
        "pagination.total_header must read a row count: a whole number from 0, or the total of a range (items 0-19/57)";

    /** #1866: the Domino Data Service sends the view's count as {@code Content-Range: items 0-19/57}. */
    @ParameterizedTest
    @ValueSource(strings = {"items 0-19/57", " items 0-19/57 ", "items */57", "rows 20-39/57"})
    void declaredItems_from_a_header_reads_the_total_of_a_range(String value) {
        RestResponse page = RestResponse.of(200, Map.of("Content-Range", List.of(value)), "[{}]");
        assertEquals(OptionalLong.of(57), PaginationSupport.declaredItems(page, null, "Content-Range"));
    }

    /** A range whose total is unknown is no count; neither is a malformed one, nor a {@code bytes} range, whose total counts bytes. */
    @ParameterizedTest
    @ValueSource(strings = {"items 0-19/*", "items 0-19", "items 0-19/-1", "items 0-19/5.5", "items a-b/57", "0-19/57",
        "bytes 0-19/57", "Bytes */57", "BYTES 0-19/57"})
    void declaredItems_from_a_header_refuses_a_range_without_a_known_row_total(String value) {
        RestResponse page = RestResponse.of(200, Map.of("Content-Range", List.of(value)), "[{}]");
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> PaginationSupport.declaredItems(page, null, "Content-Range"));
        assertEquals(ROW_COUNT_REFUSAL, e.getMessage());
    }

    @Test
    void declaredItems_without_a_header_reads_the_body_as_before() throws Exception {
        RestResponse page = RestResponse.of(200, Map.of("X-Total-Count", List.of("9")), "{\"count\":7}");
        assertEquals(OptionalLong.of(7), PaginationSupport.declaredItems(page, "$.count", null));
        assertEquals(OptionalLong.of(7), PaginationSupport.declaredItems(page, "$.count", " "), "a blank header name is none");
    }
}
