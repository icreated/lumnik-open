package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one hook-array parser both manifest loaders (MappingLoader, CsvFileConnectorType) share. The
 * divergence it replaces: MappingLoader threw on a malformed entry, CsvFileConnectorType silently
 * dropped it, so a bad hook vanished from a csv mapping while refusing a jdbc/manifest one.
 */
class HookInvocationTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static List<HookInvocation> parse(String yaml) throws Exception {
    return HookInvocation.parseList(YAML.readTree(yaml));
  }

  @Test
  void a_malformed_entry_is_refused_not_silently_dropped() throws Exception {
    // An array entry and a two-key entry: neither is a single-key hook object.
    assertThatThrownBy(() -> parse("- [a, b]\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("each hook must be an object with one key");
    assertThatThrownBy(() -> parse("- {a: 1, b: 2}\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("each hook must be an object with one key");
  }

  @Test
  void the_hook_value_may_be_an_object_a_scalar_or_null() throws Exception {
    // Each hook reads its own params: skip-empty-rows takes a boolean, map-columns a map, and a bare
    // key is null (no knobs). The parser does not second-guess the value's shape — the hook does.
    assertThatCode(() -> {
      List<HookInvocation> hooks = parse("""
          - skip-empty-rows: true
          - map-columns: { rename: { A: b } }
          - clean:
          """);
      assertThat(hooks).extracting(HookInvocation::name)
          .containsExactly("skip-empty-rows", "map-columns", "clean");
    }).doesNotThrowAnyException();
  }

  @Test
  void a_null_or_non_array_yields_no_hooks() throws Exception {
    assertThat(HookInvocation.parseList(null)).isEmpty();
    assertThat(parse("{}\n")).isEmpty();   // an object, not an array of hooks
  }
}
