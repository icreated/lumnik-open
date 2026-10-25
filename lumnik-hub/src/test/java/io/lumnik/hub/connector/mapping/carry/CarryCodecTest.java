package io.lumnik.hub.connector.mapping.carry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.lumnik.hub.connector.mapping.writer.RowHasher;
import io.lumnik.hub.connector.mapping.writer.SchemaInferrer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1452: the carry is stored typed, over a closed list, so a resumed run lands the same types and hashes. */
class CarryCodecTest {

  static Stream<Object> scalars() {
    return Stream.of("Bâche d'hiver", true, 7, 7L, (short) 7, (byte) 7, new BigInteger("123456789012345678901"),
        new BigDecimal("1000.5000"), new BigDecimal("1E+3"), 0.1d, 0.1f,
        LocalDate.of(2026, 10, 22), java.sql.Date.valueOf(LocalDate.of(2026, 10, 22)),
        new java.sql.Date(1_761_000_000_123L), new Date(1_761_000_000_123L),
        Instant.parse("2026-10-22T08:01:02.123456789Z"), LocalDateTime.of(2026, 10, 22, 8, 1, 2, 5),
        OffsetDateTime.of(2026, 10, 22, 8, 1, 2, 0, ZoneOffset.ofHours(2)),
        ZonedDateTime.of(2026, 10, 22, 8, 1, 2, 0, ZoneId.of("Europe/Paris")),
        Timestamp.from(Instant.parse("2026-10-22T08:01:02.123456789Z")));
  }

  /** A scalar comes back equal and of the very same class: the inferred column type and the row hash follow. */
  @ParameterizedTest
  @MethodSource("scalars")
  void a_scalar_round_trips_with_its_class(Object value) {
    Object back = roundTrip(value);
    assertThat(back).isEqualTo(value);
    assertThat(back.getClass()).isEqualTo(value.getClass());
  }

  static Stream<Object> containers() {
    List<Object> withNull = new ArrayList<>(List.of("a"));
    withNull.add(null);
    Map<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("z", 1L);
    ordered.put("a", null);
    ordered.put("m", List.of(LocalDate.of(2026, 1, 2), new BigDecimal("2.50")));
    return Stream.of(List.of("x", "y"), withNull, ordered, List.of(ordered, List.of()));
  }

  /** A container decodes to ArrayList / LinkedHashMap: same content, same inferred type, same row hash. */
  @ParameterizedTest
  @MethodSource("containers")
  void a_container_round_trips_to_its_canonical_shape(Object value) {
    Object back = roundTrip(value);
    assertThat(back).isEqualTo(value);
    assertThat(back).isInstanceOfAny(ArrayList.class, LinkedHashMap.class);
    Map<String, Object> row = new HashMap<>();
    row.put("c", value);
    Map<String, Object> backRow = new HashMap<>();
    backRow.put("c", back);
    assertThat(new SchemaInferrer().infer(List.of(backRow))).isEqualTo(new SchemaInferrer().infer(List.of(row)));
    assertThat(new RowHasher().hash(backRow)).isEqualTo(new RowHasher().hash(row));
  }

  /** jsonb drops object key order, and a nested map is hashed in its order: a map value is stored as pairs. */
  @Test
  void a_map_value_is_stored_as_ordered_pairs() {
    Map<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("z", "1");
    ordered.put("a", "2");
    JsonNode stored = CarryCodec.encode(carryOf(ordered)).path("declarations").path("cell[0]").path("c");
    assertThat(stored.path("t").asText()).isEqualTo("map");
    assertThat(stored.path("v").isArray()).isTrue();
    assertThat(stored.path("v").get(0).get(0).asText()).isEqualTo("z");
  }

  @Test
  void a_class_outside_the_list_is_refused_by_name() {
    assertThatThrownBy(() -> CarryCodec.encode(carryOf(UUID.randomUUID())))
        .isInstanceOf(CarryRefusedException.class)
        .hasMessageContaining("cell[0]").hasMessageContaining("'c'").hasMessageContaining("java.util.UUID");
  }

  @Test
  void a_map_with_a_key_that_is_not_text_is_refused() {
    assertThatThrownBy(() -> CarryCodec.encode(carryOf(Map.of(1, "x"))))
        .isInstanceOf(CarryRefusedException.class).hasMessageContaining("key");
  }

  /** A null key is a key that is not text: refused by name, not a NullPointerException. */
  @Test
  void a_map_with_a_null_key_is_refused_by_name() {
    Map<String, Object> withNullKey = new HashMap<>();
    withNullKey.put(null, "x");
    assertThatThrownBy(() -> CarryCodec.encode(carryOf(withNullKey)))
        .isInstanceOf(CarryRefusedException.class).hasMessageContaining("cell[0]").hasMessageContaining("key");
  }

  /** Stored values that do not read (a malformed pair, a number that is not one) fail closed, never a raw exception. */
  @ParameterizedTest
  @ValueSource(strings = {
      "{\"t\":\"map\",\"v\":[[\"k\"]]}",
      "{\"t\":\"map\",\"v\":[\"k\"]}",
      "{\"t\":\"long\",\"v\":\"twelve\"}",
      "{\"t\":\"date\",\"v\":\"22/10/2026\"}",
      "{\"t\":\"list\",\"v\":\"x\"}",
      "{\"t\":\"map\",\"v\":\"x\"}",
      "{\"t\":\"map\",\"v\":[[\"k\",{\"t\":\"null\"},\"extra\"]]}",
      "{\"t\":\"map\",\"v\":[[1,{\"t\":\"null\"}]]}",
      "{\"t\":\"text\"}",
      "{\"t\":\"long\",\"v\":7}"})
  void a_malformed_stored_value_is_stale(String value) throws Exception {
    JsonNode stored = new ObjectMapper().readTree(
        "{\"version\":1,\"fingerprint\":\"f\",\"declarations\":{\"cell[0]\":{\"c\":" + value + "}}}");
    assertThatThrownBy(() -> CarryCodec.decode(stored))
        .isInstanceOf(StaleCarryException.class).hasMessageContaining("lm endpoint reset-cursor");
  }

  @Test
  void a_carry_above_64_KiB_is_refused() {
    assertThatThrownBy(() -> CarryCodec.encode(carryOf("x".repeat(CarryCodec.MAX_BYTES))))
        .isInstanceOf(CarryRefusedException.class).hasMessageContaining("64 KiB");
  }

  @Test
  void nothing_stored_decodes_as_no_carry_and_another_version_is_stale() throws Exception {
    assertThat(CarryCodec.decode(null)).isNull();
    assertThat(CarryCodec.decode(NullNode.instance)).isNull();
    JsonNode v2 = new ObjectMapper().readTree("{\"version\":2,\"fingerprint\":\"f\",\"declarations\":{}}");
    assertThatThrownBy(() -> CarryCodec.decode(v2)).isInstanceOf(StaleCarryException.class);
  }

  private static MappingCarry carryOf(Object value) {
    Map<String, Object> columns = new HashMap<>();
    columns.put("c", value);
    return new MappingCarry("f", Map.of("cell[0]", columns));
  }

  private static Object roundTrip(Object value) {
    MappingCarry back = CarryCodec.decode(CarryCodec.encode(carryOf(value)));
    assertThat(back.fingerprint()).isEqualTo("f");
    return back.declarations().get("cell[0]").get("c");
  }
}
