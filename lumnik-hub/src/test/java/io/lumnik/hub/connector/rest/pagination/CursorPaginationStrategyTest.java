package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // ── #1611: without has_more_path the cursor decides (the Kubernetes API's metadata.continue) ──

    CursorPaginationStrategy k8s = new CursorPaginationStrategy(Map.of(
        "cursor_param", "continue",
        "cursor_path", "$.metadata.continue",
        "response_path", "$.items"));

    @Test void without_has_more_path_a_non_empty_cursor_means_more() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"metadata\":{\"continue\":\"eyJ2IjoibWV0YSJ9\"},\"items\":[{}]}");
        RestRequest next = k8s.next(r, RestRequest.get("https://k8s/api/v1/pods").withQuery("limit", "2"));
        assertEquals("eyJ2IjoibWV0YSJ9", next.queryParams().get("continue"));
        assertEquals("2", next.queryParams().get("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"metadata\":{\"continue\":\"\"},\"items\":[{}]}", "{\"metadata\":{},\"items\":[{}]}"})
    void without_has_more_path_an_empty_or_absent_cursor_is_the_last_page(String body) {
        assertNull(k8s.next(RestResponse.of(200, Map.of(), body), RestRequest.get("https://k8s/api/v1/pods")));
    }

    @Test void a_has_more_path_that_reads_no_boolean_is_refused_by_its_key_not_its_value() {
        CursorPaginationStrategy misread = new CursorPaginationStrategy(Map.of(
            "cursor_param", "continue", "cursor_path", "$.metadata.continue",
            "has_more_path", "$.metadata.continue", "response_path", "$.items"));
        RestResponse r = RestResponse.of(200, Map.of(), "{\"metadata\":{\"continue\":\"s3cr3t-token\"},\"items\":[{}]}");
        ConnectorException e = assertThrows(ConnectorException.class, () -> misread.next(r, RestRequest.get("https://k8s")));
        assertEquals("pagination.has_more_path must read a boolean, not a string", e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"));
    }

    /** A last-item cursor ($.data[-1:].id) on an empty page reads an empty list: the end, not a cursor "[]". */
    @Test void without_has_more_path_an_empty_page_ends_a_last_item_cursor() {
        CursorPaginationStrategy lastItem = new CursorPaginationStrategy(Map.of(
            "cursor_param", "starting_after", "cursor_path", "$.data[-1:].id", "response_path", "$.data"));
        assertEquals("b", lastItem.next(RestResponse.of(200, Map.of(), "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"),
            RestRequest.get("https://x")).queryParams().get("starting_after"));
        assertNull(lastItem.next(RestResponse.of(200, Map.of(), "{\"data\":[]}"), RestRequest.get("https://x")));
    }

    /** code-review of #1611: an API that hands a fresh token on an empty page would be followed to MAX_PAGES. */
    @Test void without_has_more_path_an_empty_page_ends_the_read_whatever_its_cursor() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"metadata\":{\"continue\":\"fresh-token\"},\"items\":[]}");
        assertNull(k8s.next(r, RestRequest.get("https://k8s/api/v1/pods")));
    }

    /** code-review of #1611: the flag says more and the cursor says nothing — refused, never a silent partial read. */
    @Test void a_flag_saying_more_with_no_cursor_is_refused() {
        RestResponse r = RestResponse.of(200, Map.of(), "{\"has_more\":true,\"data\":[]}");
        ConnectorException e = assertThrows(ConnectorException.class, () -> s.next(r, RestRequest.get("https://x")));
        assertEquals("pagination.has_more_path says more pages, but cursor_path reads no cursor", e.getMessage());
    }
}
