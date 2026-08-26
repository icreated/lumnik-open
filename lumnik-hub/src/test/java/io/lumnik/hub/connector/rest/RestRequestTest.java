package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RestRequestTest {
    @Test
    void withQuery_returns_new_instance_and_preserves_original() {
        RestRequest original = RestRequest.get("https://api.example.com/v1/charges");
        RestRequest mutated  = original.withQuery("starting_after", "ch_42");
        assertNotSame(original, mutated);
        assertEquals(Map.of(), original.queryParams());
        assertEquals("ch_42", mutated.queryParams().get("starting_after"));
    }

    @Test
    void withHeader_returns_new_instance() {
        RestRequest original = RestRequest.get("https://api.example.com");
        RestRequest signed   = original.withHeader("Authorization", "Bearer abc");
        assertNotSame(original, signed);
        assertNull(original.headers().get("Authorization"));
        assertEquals("Bearer abc", signed.headers().get("Authorization"));
    }
}
