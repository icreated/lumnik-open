package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A promote path reads one value (#1619), and a path that cannot be read is one cell, never the run (#1620).
 * Kubernetes' shape: a pod's conditions, Ready among them — or none yet.
 */
class PromotePathReadsOneValueTest {

  private static final Map<String, Object> READY = Map.of("status", Map.of("conditions", List.of(
      Map.of("type", "Initialized", "status", "True"), Map.of("type", "Ready", "status", "False"))));
  private static final Map<String, Object> TWO_READY = Map.of("status", Map.of("conditions", List.of(
      Map.of("type", "Ready", "status", "True"), Map.of("type", "Ready", "status", "False"))));
  private static final Map<String, Object> NO_CONDITION = Map.of("status", Map.of("conditions", List.of()));

  private static Map<String, Object> read(String path, SqlType type, Map<String, Object> item, int[] unreadable) {
    HybridJsonbSpec spec = new HybridJsonbSpec("ext.t", "$.id",
        List.of(new PromotedColumn("v", path, type, Optional.empty())));
    return HybridJsonbWriter.promoted(spec, List.of(item), i -> unreadable[0]++).getFirst();
  }

  @Test
  void a_filter_matching_one_element_reads_that_value() {
    int[] unreadable = {0};
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.TEXT, READY, unreadable)).containsEntry("v", "False");
    Object promoted = read("$.status.conditions[?(@.type=='Ready')].status", SqlType.BOOLEAN, READY, unreadable).get("v");
    assertThat(HybridJsonbWriter.converted(promoted, SqlType.BOOLEAN)).as("the column's type, as written").isEqualTo(false);
    assertThat(unreadable[0]).isZero();
  }

  @Test
  void a_filter_matching_nothing_reads_no_value() {
    int[] unreadable = {0};
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.TEXT, NO_CONDITION, unreadable)).containsEntry("v", null);
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.JSONB, NO_CONDITION, unreadable).get("v"))
        .as("jsonb keeps the list, empty").isEqualTo(List.of());
    assertThat(unreadable[0]).isZero();
  }

  @Test
  void a_filter_matching_several_values_is_one_unreadable_cell() {
    int[] unreadable = {0};
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.TEXT, TWO_READY, unreadable)).containsEntry("v", null);
    assertThat(unreadable[0]).isEqualTo(1);
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.JSONB, TWO_READY, unreadable).get("v"))
        .as("jsonb keeps every value").isEqualTo(List.of("True", "False"));
    assertThat(unreadable[0]).as("and counts no cell error").isEqualTo(1);
  }

  @Test
  void jsonb_keeps_the_list_and_a_definite_path_keeps_its_array() {
    int[] unreadable = {0};
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status", SqlType.JSONB, READY, unreadable).get("v"))
        .isEqualTo(new ArrayList<>(List.of("False")));
    assertThat(read("$.status.conditions", SqlType.TEXT, NO_CONDITION, unreadable).get("v")).isEqualTo(List.of());
  }

  /**
   * The case that failed a whole run on kind (#1620): in json-path 2.9, `first()` after a filter on a nested path throws
   * on every item the filter matches ("…using empty array", whatever the array). One unreadable cell, not the run — and
   * since #1619 the filter alone reads the value. An item it matches nothing in reads no value.
   */
  @Test
  void a_function_that_throws_is_one_unreadable_cell_not_the_run() {
    int[] unreadable = {0};
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status.first()", SqlType.TEXT, READY, unreadable)).containsEntry("v", null);
    assertThat(read("$.status.conditions[?(@.type=='Ready')].status.first()", SqlType.TEXT, NO_CONDITION, unreadable)).containsEntry("v", null);
    assertThat(unreadable[0]).as("the matched item only").isEqualTo(1);
  }
}
