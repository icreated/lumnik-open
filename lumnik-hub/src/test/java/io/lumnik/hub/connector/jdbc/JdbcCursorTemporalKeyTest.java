package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The cursor is persisted as JSON (a jsonb column) and read back by the next chunk. A primary-key value
 * must come out as the SAME Java type it went in: Jackson writes a {@code Timestamp} as a number, and a
 * number cannot be compared to a timestamp column (found on Oracle DATE, which fails with ORA-00932 from
 * the second chunk; PostgreSQL does the same).
 */
class JdbcCursorTemporalKeyTest {

  private static final ObjectMapper M = new ObjectMapper();

  /** Through the text the engine would store, not just through the in-memory tree. */
  private static JdbcCursor persisted(JdbcCursor c) throws Exception {
    JsonNode tree = M.readTree(M.writeValueAsString(c.toConnectorCursor().value()));
    return JdbcCursor.fromConnectorCursor(new ConnectorCursor(tree, Instant.now()));
  }

  @Test
  void a_timestamp_key_comes_back_a_timestamp_to_the_nanosecond() throws Exception {
    Timestamp ts = Timestamp.valueOf("2026-03-01 10:00:00.123456789");
    Map<String, Object> pk = new LinkedHashMap<>();
    pk.put("ITEM_ID", 7L);
    pk.put("VALID_FROM", ts);
    JdbcCursor back = persisted(new JdbcCursor("", null, pk));
    assertInstanceOf(Timestamp.class, back.pk().get("VALID_FROM"));
    assertEquals(ts, back.pk().get("VALID_FROM"));
    assertEquals(7L, ((Number) back.pk().get("ITEM_ID")).longValue(), "other keys are untouched");
  }

  @Test
  void a_date_key_comes_back_a_date() throws Exception {
    Date d = Date.valueOf("2026-03-01");
    JdbcCursor back = persisted(new JdbcCursor("", null, Map.of("DAY", d)));
    assertInstanceOf(Date.class, back.pk().get("DAY"));
    assertEquals(d, back.pk().get("DAY"));
  }

  @Test
  void a_time_key_comes_back_a_time() throws Exception {
    Time t = Time.valueOf("10:15:30");
    JdbcCursor back = persisted(new JdbcCursor("", null, Map.of("AT", t)));
    assertInstanceOf(Time.class, back.pk().get("AT"));
    assertEquals(t, back.pk().get("AT"));
  }

  @Test
  void a_uuid_key_comes_back_a_uuid() throws Exception {
    // A string cannot be compared to a uuid column: "operator does not exist: uuid > character varying".
    UUID id = UUID.fromString("3f2b8a52-6c1e-4d9a-9b0e-1a2b3c4d5e6f");
    JdbcCursor back = persisted(new JdbcCursor("", null, Map.of("ID", id)));
    assertInstanceOf(UUID.class, back.pk().get("ID"));
    assertEquals(id, back.pk().get("ID"));
  }

  @Test
  void a_cursor_written_before_this_change_still_reads() throws Exception {
    // Old shape: no type information, plain numbers and strings. It must keep working unchanged.
    JsonNode old = M.readTree("{\"type\":\"JdbcWatermark\",\"col\":\"\",\"keys\":[\"id\"],\"pk\":{\"id\":42}}");
    JdbcCursor back = JdbcCursor.fromConnectorCursor(new ConnectorCursor(old, Instant.now()));
    assertEquals(42, ((Number) back.pk().get("id")).intValue());
  }

  @Test
  void ordinary_keys_are_written_exactly_as_before() throws Exception {
    JsonNode tree = new JdbcCursor("", null, Map.of("id", 42L)).toConnectorCursor().value();
    JsonNode stored = M.readTree(M.writeValueAsString(tree));
    assertEquals(42, stored.get("pk").get("id").asInt());
    assertEquals(false, stored.has("pkTypes"), "no type map is added when no key needs one");
  }
}
