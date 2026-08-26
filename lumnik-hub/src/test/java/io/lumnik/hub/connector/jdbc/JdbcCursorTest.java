package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcCursorTest {

  @Test
  void empty_cursor_is_empty() {
    JdbcCursor c = JdbcCursor.empty();
    assertEquals("", c.col());
    assertNull(c.value());
    assertTrue(c.pk().isEmpty());
  }

  @Test
  void round_trip_json_with_timestamp_watermark() {
    JdbcCursor original = new JdbcCursor(
        "updated", "2026-05-23T10:00:00Z",
        Map.of("c_bpartner_id", 42L));
    ConnectorCursor encoded = original.toConnectorCursor();
    JdbcCursor decoded = JdbcCursor.fromConnectorCursor(encoded);
    assertEquals(original.col(), decoded.col());
    assertEquals(original.value(), decoded.value());
    assertEquals(42L, ((Number) decoded.pk().get("c_bpartner_id")).longValue());
  }

  @Test
  void round_trip_json_with_composite_pk() {
    JdbcCursor original = new JdbcCursor(
        "updated", "2026-05-23T10:00:00Z",
        Map.of("client_id", 1L, "order_id", 99L));
    JdbcCursor decoded = JdbcCursor.fromConnectorCursor(original.toConnectorCursor());
    assertEquals(2, decoded.pk().size());
  }

  @Test
  void from_empty_connector_cursor_returns_empty() {
    JdbcCursor c = JdbcCursor.fromConnectorCursor(ConnectorCursor.empty());
    assertEquals(JdbcCursor.empty(), c);
  }

  @Test
  void encodes_type_discriminator() {
    JdbcCursor c = new JdbcCursor("id", 42, Map.of("id", 42));
    ConnectorCursor encoded = c.toConnectorCursor();
    assertEquals("JdbcWatermark", encoded.value().get("type").asText());
  }
}
