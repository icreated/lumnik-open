package io.lumnik.hub.connector.rest.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestEndpointReaderTest {

    @Test
    void detects_graphql_by_query() {
        assertTrue(RestEndpointReader.isGraphQlEndpoint(Map.of("query", "query { x }")));
    }

    @Test
    void detects_graphql_by_kind_even_without_query() {
        // Malformed/legacy config: kind says graphql but the query is gone. Must still select the
        // GraphQL validator (fail loud) rather than the Noop validator (silent empty batch).
        assertTrue(RestEndpointReader.isGraphQlEndpoint(Map.of("kind", "graphql")));
    }

    @Test
    void rest_endpoint_is_not_graphql() {
        assertFalse(RestEndpointReader.isGraphQlEndpoint(Map.of("kind", "rest-generic")));
        assertFalse(RestEndpointReader.isGraphQlEndpoint(new HashMap<>()));
    }

    @Test
    void soap_endpoint_detected_by_kind() {
        assertTrue(RestEndpointReader.isSoapEndpoint(Map.of("kind", "soap")));
    }

    @Test
    void non_soap_endpoints_are_not_soap() {
        assertFalse(RestEndpointReader.isSoapEndpoint(Map.of("kind", "graphql")));
        assertFalse(RestEndpointReader.isSoapEndpoint(Map.of()));
    }
}
