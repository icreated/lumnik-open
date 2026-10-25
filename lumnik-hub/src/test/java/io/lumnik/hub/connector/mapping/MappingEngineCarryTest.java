package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.connector.mapping.MappingTestFixtures.hook;
import static io.lumnik.hub.connector.mapping.MappingTestFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;

/** #1452: a cell hook's carried values, per declaration, seeded from the previous chunk and snapshotted after. */
@QuarkusTest
class MappingEngineCarryTest {

  private static final ObjectMapper M = new ObjectMapper();

  @Inject MappingEngine engine;

  private static List<Map<String, Object>> rows(String... values) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      out.add(new HashMap<>(Map.of("row_no", String.valueOf(i + 1), "d", values[i])));
    }
    return out;
  }

  /** Two chunks prepared one after the other, the second seeded with the first's snapshot: one sequence. */
  @Test
  void a_seeded_chunk_continues_the_previous_one() throws Exception {
    MappingSpec spec = spec(ErrorPolicy.soft(), hook("test-carry", "{\"column\":\"d\"}"));
    PreparedChunk first = engine.prepare(spec, rows("a", "b"), 1L, UUID.randomUUID(), null);
    PreparedChunk second = engine.prepare(spec, rows("c"), 1L, UUID.randomUUID(), first.state().carried());

    assertThat(second.rows().get(0)).containsEntry("seq", 3L).containsEntry("prev", "b");
    assertThat(first.state().carried()).containsOnlyKeys("cell[0]");
  }

  /** Each declaration has its own state: the same hook declared twice counts twice, apart. */
  @Test
  void each_declaration_carries_its_own_values() throws Exception {
    MappingSpec spec = spec(ErrorPolicy.soft(), hook("test-carry", "{\"column\":\"d\"}"),
        hook("upper", "{\"columns\":[\"d\"]}"), hook("test-carry", "{\"column\":\"d\"}"));
    PreparedChunk chunk = engine.prepare(spec, rows("a", "b"), 1L, UUID.randomUUID(), null);

    assertThat(chunk.state().carried()).containsOnlyKeys("cell[0]", "cell[2]");
    assertThat(chunk.state().carried().get("cell[0]")).containsEntry("last", "b");
    assertThat(chunk.state().carried().get("cell[2]")).containsEntry("last", "B");
  }

  /** A typed value survives: parse-date before the hook carries a LocalDate, not its text. */
  @Test
  void a_typed_value_is_carried_as_its_type() throws Exception {
    MappingSpec spec = spec(ErrorPolicy.soft(), hook("parse-date", "{\"column\":\"d\",\"formats\":[\"yyyyMMdd\"]}"),
        hook("test-carry", "{\"column\":\"d\"}"));
    PreparedChunk chunk = engine.prepare(spec, rows("20261022", "20261023"), 1L, UUID.randomUUID(), null);

    assertThat(chunk.rows().get(1).get("prev")).isEqualTo(LocalDate.of(2026, 10, 22));
  }

  /** A row dead-lettered before the carrying hook never reaches it; one dead-lettered after it has moved the carry. */
  @Test
  void a_row_dead_lettered_before_the_hook_moves_nothing() throws Exception {
    ErrorPolicy dlq = ErrorPolicy.parse(M.readTree("{\"default\":\"dlq\"}"));
    MappingSpec before = spec(dlq, hook("parse-date", "{\"column\":\"d\",\"formats\":[\"yyyyMMdd\"]}"),
        hook("test-carry", "{\"column\":\"d\"}"));
    PreparedChunk b = engine.prepare(before, rows("20261022", "oops", "20261024"), 1L, UUID.randomUUID(), null);
    assertThat(b.state().carried().get("cell[1]")).containsEntry("seq", 2L);

    MappingSpec after = spec(dlq, hook("test-carry", "{\"column\":\"d\"}"),
        hook("parse-date", "{\"column\":\"d\",\"formats\":[\"yyyyMMdd\"]}"));
    PreparedChunk a = engine.prepare(after, rows("20261022", "oops", "20261024"), 1L, UUID.randomUUID(), null);
    assertThat(a.rows()).hasSize(2);
    assertThat(a.state().carried().get("cell[0]")).containsEntry("seq", 3L);
  }

  @Test
  void only_a_carrying_hook_makes_a_mapping_carry_state() throws Exception {
    assertThat(engine.carriesState(List.of(hook("upper", "{\"columns\":[\"d\"]}")))).isFalse();
    assertThat(engine.carriesState(List.of(hook("upper", "{\"columns\":[\"d\"]}"),
        hook("test-carry", "{\"column\":\"d\"}")))).isTrue();
  }
}
