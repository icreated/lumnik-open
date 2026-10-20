package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.validator.JsonNodes;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestConnectorApplyHandlerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode node(String json) throws Exception {
        return M.readTree(json).path("graphql_path");
    }

    @Test
    void textOrNull_keeps_a_real_string() throws Exception {
        assertEquals("/api/graphql", JsonNodes.textOrNull(node("{\"graphql_path\":\"/api/graphql\"}")));
    }

    @Test
    void textOrNull_rejects_explicit_null() throws Exception {
        // NullNode.asText() == "null" — must NOT be persisted (would build base_url + "null").
        assertNull(JsonNodes.textOrNull(node("{\"graphql_path\":null}")));
    }

    @Test
    void textOrNull_rejects_blank_and_missing() throws Exception {
        assertNull(JsonNodes.textOrNull(node("{\"graphql_path\":\"   \"}")));
        assertNull(JsonNodes.textOrNull(node("{}")));
    }

    // ── apply-path tests (KafkaConnectorApplyHandlerTest pattern: public apply(), mocked repos) ──

    /** Applies {@code doc} through a bare handler whose connector repository answers "nothing exists yet". */
    private static void apply(String doc, ConnectorRepository connRepo, ConnectorEndpointRepository epRepo)
            throws Exception {
        @SuppressWarnings("unchecked")
        PanacheQuery<Connector> emptyQuery = mock(PanacheQuery.class);
        when(emptyQuery.firstResultOptional()).thenReturn(Optional.empty());
        when(connRepo.find(anyString(), any(Object[].class))).thenReturn(emptyQuery);

        var handler = new RestConnectorApplyHandler();
        handler.connRepo = connRepo;
        handler.epRepo = epRepo;
        handler.apply(M.readTree(doc), 1L);
    }

    /** A bare handler whose repositories answer "nothing exists yet"; captures the persisted connector. */
    private static Connector applyAndCapture(String doc) throws Exception {
        ConnectorRepository connRepo = mock(ConnectorRepository.class);
        apply(doc, connRepo, mock(ConnectorEndpointRepository.class));
        ArgumentCaptor<Connector> captor = ArgumentCaptor.forClass(Connector.class);
        Mockito.verify(connRepo).persist(captor.capture());
        return captor.getValue();
    }

    // The validator advertises spec.default_rate_limit; the runtime reads it off the connector
    // config — apply must carry it across or the knob silently falls back to 100/s.
    @Test
    void apply_carries_default_rate_limit_into_connector_config() throws Exception {
        Connector conn = applyAndCapture(
            "{\"metadata\":{\"name\":\"c1\",\"connector_type\":\"rest-generic\"},"
            + "\"spec\":{\"base_url\":\"https://api.example.com\",\"default_rate_limit\":\"5/m\"}}");
        assertEquals("5/m", conn.getConfig().get("default_rate_limit"));
    }

    @Test
    void apply_skips_explicit_null_default_rate_limit() throws Exception {
        Connector conn = applyAndCapture(
            "{\"metadata\":{\"name\":\"c1\"},"
            + "\"spec\":{\"base_url\":\"https://api.example.com\",\"default_rate_limit\":null}}");
        assertFalse(conn.getConfig().containsKey("default_rate_limit"),
            "an explicit null must not persist the string \"null\"");
    }

    // Pins the put-ordering: an explicit spec-level value must win over the defaults.* passthrough.
    @Test
    void apply_specLevel_rate_limit_wins_over_defaults_passthrough() throws Exception {
        Connector conn = applyAndCapture(
            "{\"metadata\":{\"name\":\"c1\"},"
            + "\"spec\":{\"base_url\":\"https://api.example.com\","
            + "\"defaults\":{\"default_rate_limit\":\"50/s\"},"
            + "\"default_rate_limit\":\"5/m\"}}");
        assertEquals("5/m", conn.getConfig().get("default_rate_limit"));
    }

    /** The method stored for the one endpoint of {@code endpoint} (JSON), when the manifest leaves it to the default. */
    private static String storedMethod(String endpoint) throws Exception {
        ConnectorEndpointRepository epRepo = mock(ConnectorEndpointRepository.class);
        apply("{\"metadata\":{\"name\":\"c1\"},"
            + "\"spec\":{\"base_url\":\"https://api.example.com\",\"endpoints\":[" + endpoint + "]}}",
            mock(ConnectorRepository.class), epRepo);

        ArgumentCaptor<ConnectorEndpoint> captor = ArgumentCaptor.forClass(ConnectorEndpoint.class);
        Mockito.verify(epRepo).persist(captor.capture());
        return captor.getValue().getMethod();
    }

    // SOAP always sends POST; storing GET made a pre-flight that composed nothing print METHOD GET.
    @Test
    void apply_stores_POST_for_a_soap_endpoint_without_a_method() throws Exception {
        assertEquals("POST", storedMethod("{\"id\":\"partners\",\"path\":\"/ws\",\"kind\":\"soap\"}"));
    }

    // GraphQL is POST too, recognised as the engine does (RestEndpointReader.isGraphQlEndpoint): kind OR query.
    @Test
    void apply_stores_POST_for_a_graphql_endpoint_without_a_method() throws Exception {
        assertEquals("POST", storedMethod("{\"id\":\"partners\",\"kind\":\"graphql\",\"query\":\"{ a }\"}"));
        assertEquals("POST", storedMethod("{\"id\":\"partners\",\"query\":\"{ a }\"}"));
    }

    @Test
    void apply_stores_GET_for_a_plain_endpoint_without_a_method() throws Exception {
        assertEquals("GET", storedMethod("{\"id\":\"partners\",\"path\":\"/p\"}"));
    }
}
