package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VariableCursorBindingTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writes_cursor_into_variables_preserving_others() throws Exception {
        RestRequest req = RestRequest.post("http://x/graphql",
            "{\"query\":\"query($after:String){x}\",\"variables\":{\"first\":100}}");

        RestRequest out = new VariableCursorBinding("after").bind(req, "CUR");

        JsonNode body = M.readTree(out.body());
        assertEquals("CUR", body.path("variables").path("after").asText());
        assertEquals(100, body.path("variables").path("first").asInt());
        assertEquals("POST", out.method());
    }

    @Test
    void creates_variables_object_when_absent() throws Exception {
        RestRequest req = RestRequest.post("http://x/graphql", "{\"query\":\"q\"}");

        RestRequest out = new VariableCursorBinding("after").bind(req, "CUR");

        assertEquals("CUR", M.readTree(out.body()).path("variables").path("after").asText());
    }

    @Test
    void non_object_body_fails_with_a_clear_message() {
        // A non-object JSON body (here an array) must fail with an actionable message — not a cryptic
        // ClassCastException cause — while preserving the variable-name context.
        RestRequest req = RestRequest.post("http://x/graphql", "[1,2,3]");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new VariableCursorBinding("after").bind(req, "CUR"));

        assertTrue(ex.getMessage().contains("after"), () -> "context lost: " + ex.getMessage());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage() != null && ex.getCause().getMessage().contains("JSON object"),
            () -> "expected a clear 'not a JSON object' cause, got: " + ex.getCause());
    }
}
