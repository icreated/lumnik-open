package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL refuses U+0000 in a text value (22021) and the refusal failed the whole chunk, then the run (#1855). A
 * row that still holds one after the cell hooks is that row's problem: it is dropped with a reason that names the
 * column and the way out.
 */
@QuarkusTest
class MappingNulByteTest {

  @Inject MappingEngine engine;

  private static List<Map<String, Object>> input() {
    return List.of(
        new HashMap<>(Map.of("id", "1", "packed", "ab")),
        new HashMap<>(Map.of("id", "2", "packed", "a\u0000b")),
        new HashMap<>(Map.of("id", "3", "packed", "cd")));
  }

  @Test
  void a_row_still_holding_a_nul_byte_is_dropped_alone_and_the_reason_names_the_way_out() {
    MappingSpec spec = MappingLoader.load("id: nul\nversion: 1\ntarget: x\n");

    MappingState state = new MappingState();
    var out = engine.transform(spec, input(), state, false);

    assertThat(out).extracting(r -> r.get("id")).containsExactly("1", "3");
    assertThat(state.dropped()).hasSize(1);
    var drop = state.dropped().getFirst();
    assertThat(drop.reason()).isInstanceOfSatisfying(SkipReason.MappingFailed.class, m -> {
      assertThat(m.field()).isEqualTo("packed");
      assertThat(m.message()).contains("NUL byte").contains("remove-control-chars");
    });
    assertThat(drop.row()).containsEntry("id", "2");
  }

  /** A nested value lands in a {@code jsonb} column, which refuses the byte too, and no hook reaches into it. */
  @Test
  void a_nul_byte_inside_a_nested_value_drops_the_row_and_says_no_hook_reaches_it() {
    MappingSpec spec = MappingLoader.load("id: nul-nested\nversion: 1\ntarget: x\n");
    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("id", "1", "doc", Map.of("k", "ok"))),
        new HashMap<>(Map.of("id", "2", "doc", Map.of("k", List.of("a\u0000b")))));

    MappingState state = new MappingState();
    var out = engine.transform(spec, input, state, false);

    assertThat(out).extracting(r -> r.get("id")).containsExactly("1");
    assertThat(state.dropped()).singleElement().satisfies(d ->
        assertThat(d.reason()).isInstanceOfSatisfying(SkipReason.MappingFailed.class, m -> {
          assertThat(m.field()).isEqualTo("doc");
          assertThat(m.message()).contains("NUL byte").contains("nested");
        }));
  }

  @Test
  void the_clean_hook_that_removes_control_characters_keeps_the_row() {
    MappingSpec spec = MappingLoader.load("""
        id: nul-clean
        version: 1
        target: x
        cell:
          - { clean: { columns: [packed], remove-control-chars: true } }
        """);

    MappingState state = new MappingState();
    var out = engine.transform(spec, input(), state, false);

    assertThat(out).hasSize(3);
    assertThat(out.get(1)).containsEntry("packed", "ab");
    assertThat(state.dropped()).isEmpty();
  }

  /** The guard sees any text, so the advice must too: a direct text value is the clean hook's, whatever its class. */
  @Test
  void a_text_value_that_is_not_a_string_still_gets_the_clean_hook_advice() {
    MappingSpec spec = MappingLoader.load("id: nul-seq\nversion: 1\ntarget: x\n");
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("id", "1", "packed", new StringBuilder("a\u0000b"))));

    MappingState state = new MappingState();
    engine.transform(spec, input, state, false);

    assertThat(state.dropped()).singleElement().satisfies(d ->
        assertThat(((SkipReason.MappingFailed) d.reason()).message()).contains("remove-control-chars"));
  }
}
