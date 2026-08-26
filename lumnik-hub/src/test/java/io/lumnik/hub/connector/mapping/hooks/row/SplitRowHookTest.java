package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
