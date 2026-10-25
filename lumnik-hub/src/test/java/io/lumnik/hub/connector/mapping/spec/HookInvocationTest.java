package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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

  /** The tier {@code cell:} of a mapping, written as the given YAML. */
  private static List<HookInvocation> parse(String yaml) throws Exception {
    return HookInvocation.parseList(YAML.createObjectNode().set("cell", YAML.readTree(yaml)), "cell");
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

  /** #1474: the refusal names the tier, the entry's index and its shape — never a value the entry carries. */
  @Test
  void a_malformed_entry_is_refused_naming_its_place_and_shape_not_its_content() {
    assertThatThrownBy(() -> parse("- upper: { columns: [a] }\n- [SENTINEL-1474, b]\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("each hook must be an object with one key (cell[1]: got array)");
    assertThatThrownBy(() -> parse("- { enrich-context: { k: SENTINEL-1474 }, upper: { columns: [a] } }\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("each hook must be an object with one key (cell[0]: got object with 2 keys)");
    assertThatThrownBy(() -> parse("- SENTINEL-1474\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("each hook must be an object with one key (cell[0]: got string)");
  }

  @Test
  void the_hook_value_may_be_an_object_a_scalar_or_null() throws Exception {
    // The parser does not second-guess the value's shape: a block that is neither a map nor bare
    // is refused by HookCatalog, which knows the hook's name (#1492).
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
  void an_absent_or_empty_tier_yields_no_hooks() throws Exception {
    assertThat(HookInvocation.parseList(YAML.createObjectNode(), "cell")).isEmpty();
    assertThat(HookInvocation.parseList(MissingNode.getInstance(), "cell")).isEmpty();   // no mapping at all
    assertThat(parse("~\n")).isEmpty();   // `cell:` written with nothing after it
  }

  /** #1464: a tier written as a map (or a scalar) was read as no hooks — the manifest applied clean and ran none. */
  @Test
  void a_tier_that_is_not_a_list_is_refused_naming_its_shape() {
    assertThatThrownBy(() -> parse("upper: { columns: [name] }\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("cell must be a list of hooks, e.g. - upper: { columns: [name] } (got object)");
    assertThatThrownBy(() -> parse("upper\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("cell must be a list of hooks, e.g. - upper: { columns: [name] } (got string)");
  }
}
