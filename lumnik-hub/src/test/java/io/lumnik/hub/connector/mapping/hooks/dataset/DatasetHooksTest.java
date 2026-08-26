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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class DatasetHooksTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  private JsonNode yaml(String src) throws Exception {
    return yaml.readTree(src);
  }

  @Test
  void require_headers_passes_when_all_present() throws Exception {
    var hook = catalog.dataset("require-headers");
    var params = yaml("columns: [email, name]");
    var rows = List.of(Map.<String,Object>of("email","x","name","y"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).hasSize(1);
  }

  @Test
  void require_headers_throws_when_missing() throws Exception {
    var hook = catalog.dataset("require-headers");
    var params = yaml("columns: [email, name]");
    var rows = List.of(Map.<String,Object>of("email","x"));
    assertThatThrownBy(() -> hook.apply(rows, params, new MappingState()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("name");
  }

  @Test
  void require_headers_any_of_passes_when_one_present() throws Exception {
    var hook = catalog.dataset("require-headers");
    var params = yaml("any-of: [email, e-mail, Email]");
    var rows = List.of(Map.<String,Object>of("Email","x"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).hasSize(1);
  }

  @Test
  void filter_where_drops_non_matching_rows() throws Exception {
    var hook = catalog.dataset("filter-where");
    var params = yaml("column: status\nequals: active");
    var rows = List.of(
        Map.<String,Object>of("status","active"),
        Map.<String,Object>of("status","inactive"),
        Map.<String,Object>of("status","active"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).hasSize(2);
  }

  @Test
  void filter_where_in_list() throws Exception {
    var hook = catalog.dataset("filter-where");
    var params = yaml("column: type\nin: [A, B]");
    var rows = List.of(
        Map.<String,Object>of("type","A"),
        Map.<String,Object>of("type","B"),
        Map.<String,Object>of("type","C"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).hasSize(2);
  }

  @Test
  void enrich_context_adds_static_columns() throws Exception {
    var hook = catalog.dataset("enrich-context");
    var params = yaml("source: shopify\nbatch_id: '123'");
    List<Map<String,Object>> rows = List.of(new HashMap<>(Map.of("a","1")));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out.get(0)).containsEntry("source","shopify").containsEntry("batch_id","123");
  }

  @Test
  void enrich_context_now_placeholder_resolves_to_instant() throws Exception {
    var hook = catalog.dataset("enrich-context");
    var params = yaml("imported_at: \"${now}\"");
    List<Map<String,Object>> rows = List.of(new HashMap<>(Map.of("a","1")));
    var out = hook.apply(rows, params, new MappingState());
    String t = out.get(0).get("imported_at").toString();
    assertThat(t).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
  }

  @Test
  void skip_empty_rows_drops_blank_rows() throws Exception {
    var hook = catalog.dataset("skip-empty-rows");
    var params = yaml("true");
    var rows = List.of(
        Map.<String,Object>of("a","1"),
        Map.<String,Object>of("a","","b",""),
        Map.<String,Object>of("a","2"));
    var out = hook.apply(rows, params, new MappingState());
    assertThat(out).hasSize(2);
  }
}
