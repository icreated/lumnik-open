package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CursorBindingTest {

    @Test
    void query_binding_writes_to_query_params() {
        CursorBinding b = new QueryParamCursorBinding("starting_after");
        RestRequest bound = b.bind(RestRequest.get("https://x"), "ch_42");
        assertEquals("ch_42", bound.queryParams().get("starting_after"));
        assertNull(bound.headers().get("starting_after"));
    }

    @Test
    void header_binding_writes_to_headers() {
        CursorBinding b = new HeaderCursorBinding("X-Next-Cursor");
        RestRequest bound = b.bind(RestRequest.get("https://x"), "ch_42");
        assertEquals("ch_42", bound.headers().get("X-Next-Cursor"));
        assertNull(bound.queryParams().get("X-Next-Cursor"));
    }
}
