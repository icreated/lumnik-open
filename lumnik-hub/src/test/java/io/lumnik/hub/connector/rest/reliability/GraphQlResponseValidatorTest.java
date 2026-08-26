package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphQlResponseValidatorTest {

    private final GraphQlResponseValidator validator = new GraphQlResponseValidator();

    private RestResponse resp(String body) {
        return RestResponse.of(200, Map.of(), body);
    }

    @Test
    void throws_when_errors_present() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            validator.validate(resp("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}")));
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void passes_when_no_errors() {
        assertDoesNotThrow(() -> validator.validate(resp("{\"data\":{\"x\":1}}")));
    }

    @Test
    void passes_when_errors_is_empty_array() {
        assertDoesNotThrow(() -> validator.validate(resp("{\"errors\":[],\"data\":{}}")));
    }

    @Test
    void throws_on_invalid_json() {
        // An HTML proxy/WAF error page returned with HTTP 200 must not read as a silent empty batch.
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            validator.validate(resp("<html><body>502 Bad Gateway</body></html>")));
        assertTrue(ex.getMessage().contains("not valid JSON"));
    }

    @Test
    void throws_on_empty_body() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> validator.validate(resp("")));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void throws_when_neither_data_nor_errors() {
        // Valid JSON, but not a GraphQL envelope (e.g. graphql_path pointing at a REST endpoint).
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            validator.validate(resp("{\"items\":[1,2,3]}")));
        assertTrue(ex.getMessage().contains("envelope"));
    }

    @Test
    void throws_when_errors_is_not_an_array() {
        // GraphQL errors MUST be a list; a non-array errors would otherwise skip the errors check.
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            validator.validate(resp("{\"errors\":{\"message\":\"x\"},\"data\":null}")));
        assertTrue(ex.getMessage().contains("array"));
    }

    @Test
    void throws_when_data_null_without_errors() {
        // data:null with no errors is a malformed envelope — would silently ingest nothing.
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            validator.validate(resp("{\"data\":null}")));
        assertTrue(ex.getMessage().contains("data"));
    }

    @Test
    void does_not_throw_on_retryable_5xx_so_retry_can_happen() {
        // 503 with an HTML body must NOT become a permanent failure — RetryExecutor runs the
        // GraphQL validator BEFORE its status-based retry, so throwing here would block the retry.
        RestResponse r = RestResponse.of(503, Map.of(), "<html>503 Service Unavailable</html>");
        assertDoesNotThrow(() -> validator.validate(r));
    }

    @Test
    void does_not_throw_on_rate_limit_429() {
        assertDoesNotThrow(() -> validator.validate(RestResponse.of(429, Map.of(), "")));
    }
}
