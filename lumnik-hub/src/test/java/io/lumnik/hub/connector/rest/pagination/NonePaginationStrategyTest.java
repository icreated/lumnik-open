package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #1650: an endpoint that answers its whole list at once — one request, no next page. */
class NonePaginationStrategyTest {

    private final NonePaginationStrategy strategy = new NonePaginationStrategy(Map.of("response_path", "$.vetList"));

    @Test
    void first_is_a_get_to_base_plus_path() {
        RestRequest req = strategy.first(new EndpointConfig("http://clinic", "GET", "/vets.json", Map.of()), null);
        assertEquals("GET", req.method());
        assertEquals("http://clinic/vets.json", req.url());
    }

    @Test
    void there_is_never_a_next_page() {
        RestResponse resp = RestResponse.of(200, Map.of(), "{\"vetList\":[{\"id\":1}],\"next\":\"http://clinic/vets.json?page=2\"}");
        assertNull(strategy.next(resp, RestRequest.get("http://clinic/vets.json")));
    }

    @Test
    void items_are_read_at_the_response_path() {
        RestResponse resp = RestResponse.of(200, Map.of(), "{\"vetList\":[{\"id\":1},{\"id\":3}]}");
        assertEquals(2, strategy.items(resp).size());
    }

    @Test
    void a_missing_response_path_is_refused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new NonePaginationStrategy(Map.of()));
        assertEquals("none pagination requires 'response_path'", e.getMessage());
    }
}
