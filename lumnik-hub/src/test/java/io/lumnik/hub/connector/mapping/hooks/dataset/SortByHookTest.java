package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SortByHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void sorts_by_single_column() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [name]");
    var rows = List.of(
        Map.<String, Object>of("name", "charlie"),
        Map.<String, Object>of("name", "alice"),
        Map.<String, Object>of("name", "bob"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).extracting(m -> m.get("name")).containsExactly("alice", "bob", "charlie");
  }

  @Test
  void sorts_by_multiple_columns() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [country, name]");
    var rows = List.of(
        Map.<String, Object>of("country", "US", "name", "Bob"),
        Map.<String, Object>of("country", "FR", "name", "Charles"),
        Map.<String, Object>of("country", "FR", "name", "Alice"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).extracting(m -> m.get("name")).containsExactly("Alice", "Charles", "Bob");
  }

  @Test
  void empty_input_returns_empty() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [k]");
    var out = hook.apply(List.of(), params, new MappingState());
    assertThat(out).isEmpty();
  }
}
