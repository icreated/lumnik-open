package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class RowHooksBasicTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private JsonNode yaml(String src) throws Exception { return YAML.readTree(src); }

  @Test
  void map_columns_renames_and_drops() throws Exception {
    var hook = catalog.row("map-columns");
    var params = yaml("rename:\n  e-mail: email\ndrop: [_raw]");
    var row = new HashMap<String, Object>(Map.of("e-mail", "x@y", "_raw", "junk", "keep", "ok"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out).hasSize(1);
    assertThat(out.get(0))
        .containsEntry("email", "x@y")
        .containsEntry("keep", "ok")
        .doesNotContainKey("e-mail")
        .doesNotContainKey("_raw");
  }

  @Test
  void validate_row_passes_when_required_present() throws Exception {
    var hook = catalog.row("validate-row");
    var params = yaml("required: [email]");
    var out = hook.apply(new HashMap<>(Map.of("email", "x")), params, new MappingState());
    assertThat(out).hasSize(1);
  }

  @Test
  void validate_row_returns_empty_when_required_missing() throws Exception {
    var hook = catalog.row("validate-row");
    var params = yaml("required: [email]");
    MappingState state = new MappingState();
    var out = hook.apply(new HashMap<>(Map.of("name", "x")), params, state);
    assertThat(out).isEmpty();
    assertThat(state.dropped()).hasSize(1);
    assertThat(state.dropped().get(0).reason()).isInstanceOf(SkipReason.ValidationFailed.class);
  }

  @Test
  void defaults_sets_missing_columns() throws Exception {
    var hook = catalog.row("defaults");
    var params = yaml("country: FR\ncurrency: EUR");
    var row = new HashMap<String, Object>(Map.of("name", "x"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out.get(0))
        .containsEntry("country", "FR")
        .containsEntry("currency", "EUR")
        .containsEntry("name", "x");
  }

  @Test
  void defaults_does_not_overwrite_existing() throws Exception {
    var hook = catalog.row("defaults");
    var params = yaml("country: FR");
    var row = new HashMap<String, Object>(Map.of("country", "US"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out.get(0)).containsEntry("country", "US");
  }

  @Test
  void coalesce_picks_first_non_null() throws Exception {
    var hook = catalog.row("coalesce");
    var params = yaml("column: email\nfrom: [primary_email, secondary_email]");
    var row = new HashMap<String, Object>(Map.of("secondary_email", "fallback@x"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out.get(0)).containsEntry("email", "fallback@x");
  }

  @Test
  void coalesce_keeps_existing_target_if_set() throws Exception {
    var hook = catalog.row("coalesce");
    var params = yaml("column: email\nfrom: [primary, secondary]");
    var row = new HashMap<String, Object>(Map.of("email", "already@set", "secondary", "fallback"));
    var out = hook.apply(row, params, new MappingState());
    assertThat(out.get(0)).containsEntry("email", "already@set");
  }
}
