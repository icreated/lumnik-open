package io.lumnik.hub.connector.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathsTest {

    private final ObjectMapper M = new ObjectMapper();

    @Test void clean_key_uses_dot_notation() {
        assertEquals("$.order_id", JsonPaths.member("order_id"));
    }

    @Test void null_or_blank_key_is_rejected() {
        // a missing member name is a programming error — fail fast rather than pointing at a literal
        // "null"/empty key (mirrors HybridJsonbWriter.qi's identifier guard)
        assertThrows(IllegalArgumentException.class, () -> JsonPaths.member(null));
        assertThrows(IllegalArgumentException.class, () -> JsonPaths.member(""));
        assertThrows(IllegalArgumentException.class, () -> JsonPaths.member("   "));
    }

    @Test void member_reads_the_literal_key_for_dots_spaces_quotes_backslashes() throws Exception {
        ObjectNode o = M.createObjectNode();
        o.put("order.id", "v_dot");          // a flat key that contains a dot
        o.put("order id", "v_space");
        o.put("it's", "v_apostrophe");
        o.put("a\"b", "v_quote");
        o.put("a\\b", "v_backslash");
        String json = M.writeValueAsString(o);

        assertEquals("v_dot", JsonPath.read(json, JsonPaths.member("order.id")));
        assertEquals("v_space", JsonPath.read(json, JsonPaths.member("order id")));
        assertEquals("v_apostrophe", JsonPath.read(json, JsonPaths.member("it's")));
        assertEquals("v_quote", JsonPath.read(json, JsonPaths.member("a\"b")));
        assertEquals("v_backslash", JsonPath.read(json, JsonPaths.member("a\\b")));
    }
}
