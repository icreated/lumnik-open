package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
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

    /** A bare handler whose repositories answer "nothing exists yet"; captures the persisted connector. */
    private static Connector applyAndCapture(String doc) throws Exception {
        ConnectorRepository connRepo = mock(ConnectorRepository.class);
        ConnectorEndpointRepository epRepo = mock(ConnectorEndpointRepository.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Connector> emptyQuery = mock(PanacheQuery.class);
        when(emptyQuery.firstResultOptional()).thenReturn(Optional.empty());
        when(connRepo.find(anyString(), any(Object[].class))).thenReturn(emptyQuery);

        var handler = new RestConnectorApplyHandler();
        handler.connRepo = connRepo;
        handler.epRepo = epRepo;
        handler.apply(M.readTree(doc), 1L);

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
}
