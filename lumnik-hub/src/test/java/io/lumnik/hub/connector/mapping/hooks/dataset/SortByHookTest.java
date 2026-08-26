package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class SortByHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void sorts_by_single_column() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [name]\nmax-rows: 1000");
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
    var params = yaml("columns: [country, name]\nmax-rows: 1000");
    var rows = List.of(
        Map.<String, Object>of("country", "US", "name", "Bob"),
        Map.<String, Object>of("country", "FR", "name", "Charles"),
        Map.<String, Object>of("country", "FR", "name", "Alice"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).extracting(m -> m.get("name")).containsExactly("Alice", "Charles", "Bob");
  }

  @Test
  void throws_when_exceeds_max_rows() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [k]\nmax-rows: 2");
    var rows = List.of(
        Map.<String, Object>of("k", "a"),
        Map.<String, Object>of("k", "b"),
        Map.<String, Object>of("k", "c"));
    assertThatThrownBy(() -> hook.apply(rows, params, new MappingState()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("max-rows");
  }

  @Test
  void empty_input_returns_empty() throws Exception {
    var hook = catalog.dataset("sort-by");
    var params = yaml("columns: [k]\nmax-rows: 1000");
    var out = hook.apply(List.of(), params, new MappingState());
    assertThat(out).isEmpty();
  }
}
