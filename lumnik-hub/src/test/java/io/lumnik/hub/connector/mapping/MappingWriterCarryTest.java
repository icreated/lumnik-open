package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.carry.CarryCodec;
import io.lumnik.hub.connector.mapping.carry.CarryRefusedException;
import io.lumnik.hub.connector.mapping.carry.MappingCarry;
import io.lumnik.hub.connector.mapping.carry.MappingFingerprint;
import io.lumnik.hub.connector.mapping.carry.StaleCarryException;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.connector.mapping.MappingTestFixtures.hook;
import static io.lumnik.hub.connector.mapping.MappingTestFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1452: the mapping writer starts a file empty, reuses only its own mapping's carry, and hands the next one out. */
@QuarkusTest
class MappingWriterCarryTest {

  @Inject MappingEngine engine;

  private static MappingSpec carrying(String column) {
    return spec(ErrorPolicy.soft(), hook("test-carry", "{\"column\":\"" + column + "\"}"));
  }

  private static List<Map<String, Object>> rows(int n) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 1; i <= n; i++) out.add(new HashMap<>(Map.of("row_no", String.valueOf(i), "d", "v" + i)));
    return out;
  }

  @Test
  void a_chunk_continues_from_the_stored_carry() throws Exception {
    MappingSpec spec = carrying("d");
    JsonNode stored = engine.itemWriter(spec).prepare(rows(2), MappingTestFixtures.run(), null, true);

    List<Map<String, Object>> next = rows(1);
    engine.itemWriter(spec).prepare(next, MappingTestFixtures.run(), stored, false);
    assertThat(next.get(0)).containsEntry("seq", 3L).containsEntry("prev", "v2");
  }

  @Test
  void a_chunk_that_starts_a_file_starts_empty_whatever_is_stored() throws Exception {
    MappingSpec spec = carrying("d");
    JsonNode stored = CarryCodec.encode(new MappingCarry(MappingFingerprint.of(spec),
        Map.of("cell[0]", Map.of("seq", 41L, "last", "x"))));
    List<Map<String, Object>> chunk = rows(1);
    engine.itemWriter(spec).prepare(chunk, MappingTestFixtures.run(), stored, true);
    assertThat(chunk.get(0)).containsEntry("seq", 1L);
  }

  @Test
  void a_carry_of_another_mapping_is_refused_mid_file() throws Exception {
    JsonNode stored = CarryCodec.encode(new MappingCarry(MappingFingerprint.of(carrying("d")),
        Map.of("cell[0]", Map.of("seq", 1L))));
    MappingSpec changed = carrying("e");
    assertThatThrownBy(() -> engine.itemWriter(changed).prepare(rows(1), MappingTestFixtures.run(), stored, false))
        .isInstanceOf(StaleCarryException.class).hasMessageContaining("lm endpoint reset-cursor <connector>/<endpoint>").hasMessageContaining("reads the source again from its start");
  }

  @Test
  void a_resume_mid_file_without_a_carry_is_refused() throws Exception {
    MappingSpec spec = carrying("d");
    assertThatThrownBy(() -> engine.itemWriter(spec).prepare(rows(1), MappingTestFixtures.run(), null, false))
        .isInstanceOf(StaleCarryException.class).hasMessageContaining("lm endpoint reset-cursor <connector>/<endpoint>").hasMessageContaining("reads the source again from its start");
  }

  @Test
  void a_mapping_without_a_carrying_hook_ignores_and_clears_a_stored_carry() throws Exception {
    MappingSpec plain = spec(ErrorPolicy.soft(), hook("upper", "{\"columns\":[\"d\"]}"));
    JsonNode stored = CarryCodec.encode(new MappingCarry("other", Map.of("cell[0]", Map.of("seq", 1L))));
    assertThat(engine.itemWriter(plain).prepare(rows(1), MappingTestFixtures.run(), stored, false)).isNull();
  }

  @Test
  void an_unencodable_carry_fails_prepare_before_any_write() throws Exception {
    MappingSpec spec = carrying("u");
    List<Map<String, Object>> chunk = rows(1);
    chunk.get(0).put("u", UUID.randomUUID());
    assertThatThrownBy(() -> engine.itemWriter(spec).prepare(chunk, MappingTestFixtures.run(), null, true))
        .isInstanceOf(CarryRefusedException.class).hasMessageContaining("java.util.UUID");
  }
}
