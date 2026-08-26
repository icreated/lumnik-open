package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest {

    private final UnaryOperator<String> env = name -> "X-TENANT".equals(name) ? "acme" : null;

    @Test
    void applies_static_and_env_headers() {
        RestRequest req = RestRequest.get("http://x");
        Object cfg = List.of(
            Map.of("name", "X-Api-Version", "value", "2024-10"),
            Map.of("name", "X-Tenant", "value_env", "X-TENANT"));

        RestRequest out = RequestHeaders.apply(req, cfg, env);

        assertEquals("2024-10", out.headers().get("X-Api-Version"));
        assertEquals("acme", out.headers().get("X-Tenant"));
    }

    @Test
    void skips_when_cfg_is_null_or_not_a_list() {
        RestRequest req = RestRequest.get("http://x");
        assertSame(req, RequestHeaders.apply(req, null, env));
        assertSame(req, RequestHeaders.apply(req, "not-a-list", env));
    }

    @Test
    void skips_entry_with_no_value_and_no_env() {
        RestRequest out = RequestHeaders.apply(
            RestRequest.get("http://x"), List.of(Map.of("name", "X-Empty")), env);
        assertNull(out.headers().get("X-Empty"));
    }
}
