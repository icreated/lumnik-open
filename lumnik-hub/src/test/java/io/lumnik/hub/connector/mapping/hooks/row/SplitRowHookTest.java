package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@QuarkusTest
class SplitRowHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void split_by_separator_emits_multiple_rows() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: tags\nsep: ','");
    var row = new HashMap<String, Object>(Map.of("name", "p1", "tags", "a,b,c"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).hasSize(3);
    assertThat(out.get(0)).containsEntry("name", "p1").containsEntry("tags", "a");
    assertThat(out.get(1)).containsEntry("tags", "b");
    assertThat(out.get(2)).containsEntry("tags", "c");
  }

  @Test
  void split_by_separator_trims_each_part() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: tags\nsep: ','");
    var row = new HashMap<String, Object>(Map.of("tags", " a , b ,c"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).extracting(m -> m.get("tags")).containsExactly("a", "b", "c");
  }

  @Test
  void explode_list_column_emits_multiple_rows() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("explode: variants");
    var row = new HashMap<String, Object>(Map.of("name", "p1", "variants", List.of("v1", "v2")));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).hasSize(2);
    assertThat(out.get(0)).containsEntry("variants", "v1");
    assertThat(out.get(1)).containsEntry("variants", "v2");
  }

  @Test
  void unchanged_when_column_missing() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: tags\nsep: ','");
    var row = new HashMap<String, Object>(Map.of("name", "p1"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).hasSize(1);
    assertThat(out.get(0)).containsEntry("name", "p1");
  }

  /**
   * #1421: a dynamic table is content-addressed, so two identical fragments are one row — an order of two identical
   * T-shirts landed as one, counted nowhere. {@code position} writes each fragment's place in the cell, and both land.
   */
  @Test
  void position_tells_identical_parts_apart() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: items\nsep: '|'\nposition: item_no");
    var row = new HashMap<String, Object>(Map.of("order_no", "CMD-1003", "items", "TSH-BLEU-M|TSH-BLEU-M"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).extracting(m -> m.get("items"), m -> m.get("item_no"))
        .containsExactly(tuple("TSH-BLEU-M", 1),
            tuple("TSH-BLEU-M", 2));
  }

  @Test
  void position_is_on_every_row_and_null_when_the_cell_is() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: items\nsep: '|'\nposition: item_no");
    var empty = new HashMap<String, Object>();
    empty.put("items", null);
    assertThat(hook.apply(empty, params, new MappingState())).singleElement()
        .satisfies(r -> assertThat(r).containsEntry("item_no", null));
    var exploded = hook.apply(new HashMap<String, Object>(Map.of("v", List.of("a", "b"))),
        yaml("explode: v\nposition: n"), new MappingState());
    assertThat(exploded).extracting(m -> m.get("n")).containsExactly(1, 2);
  }

  @Test
  void position_must_name_another_column() throws Exception {
    var hook = catalog.row("split-row");
    assertThatThrownBy(() -> hook.validateParams(yaml("by: items\nposition: items")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'position'");
    assertThatThrownBy(() -> hook.validateParams(yaml("by: items\nposition: [a]")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'position'");
  }

  /** #1451: EDIFACT's release character — ?' is a literal apostrophe, ?? a literal ?; neither cuts the segment. */
  @Test
  void an_escaped_separator_stays_in_its_part_and_the_escape_goes() throws Exception {
    var hook = catalog.row("split-row");
    var params = yaml("by: line\nsep: \"'\"\nescape: \"?\"");
    var row = new HashMap<String, Object>(Map.of("line", "LIN+1'IMD+F++:::Bâche d?'hiver 10x5'FTX+AAA+++prix ?? ?'"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).extracting(m -> m.get("line"))
        .containsExactly("LIN+1", "IMD+F++:::Bâche d'hiver 10x5", "FTX+AAA+++prix ? '");
  }

  @Test
  void without_escape_the_split_is_unchanged() throws Exception {
    var hook = catalog.row("split-row");
    var out = hook.apply(new HashMap<String, Object>(Map.of("line", "a?'b'c")), yaml("by: line\nsep: \"'\""),
        new MappingState());
    assertThat(out).extracting(m -> m.get("line")).containsExactly("a?", "b", "c");
  }

  @Test
  void escape_must_be_one_character_other_than_the_separator() throws Exception {
    var hook = catalog.row("split-row");
    assertThatThrownBy(() -> hook.validateParams(yaml("by: line\nsep: \"'\"\nescape: \"??\"")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'escape'");
    assertThatThrownBy(() -> hook.validateParams(yaml("by: line\nsep: \"'\"\nescape: \"'\"")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'escape'");
    assertThatThrownBy(() -> hook.validateParams(yaml("by: line\nsep: \"?'\"\nescape: \"?\"")))
        .as("an escape that is part of a longer sep").isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'escape'");
  }

  @Test
  void an_escape_before_an_ordinary_character_goes_and_a_trailing_one_stays() throws Exception {
    var hook = catalog.row("split-row");
    var out = hook.apply(new HashMap<String, Object>(Map.of("line", "a?xb'c?")), yaml("by: line\nsep: \"'\"\nescape: \"?\""),
        new MappingState());
    assertThat(out).extracting(m -> m.get("line")).containsExactly("axb", "c?");
  }

  @Test
  void escape_without_by_is_refused() throws Exception {
    assertThatThrownBy(() -> catalog.row("split-row").validateParams(yaml("explode: v\nescape: \"?\"")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'escape'");
  }

  /** An empty sep beside escape would never advance (security review of #1453): refused before any row. */
  @Test
  void escape_with_an_empty_separator_is_refused() throws Exception {
    assertThatThrownBy(() -> catalog.row("split-row").validateParams(yaml("by: line\nsep: \"\"\nescape: \"?\"")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'sep'");
  }

  @Test
  void the_escaped_split_never_loops_on_an_empty_separator() {
    assertThat(SplitRowHook.splitEscaped("abc", "", '?')).containsExactly("abc");
  }

  /** Review of #1453: the escape protects the whole separator, not only its first character. */
  @Test
  void an_escaped_multi_character_separator_stays_whole() {
    assertThat(SplitRowHook.splitEscaped("a?||b||c", "||", '?')).containsExactly("a||b", "c");
  }
}
