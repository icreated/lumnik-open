package io.lumnik.hub.connector.mapping.hooks.cell;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class KotlinCellHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private JsonNode yaml(String src) throws Exception {
    return YAML.readTree(src);
  }

  @Test
  void script_uppercases_phone() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/uppercase-phone.kts
        """);
    Map<String, Object> row = new HashMap<>(Map.of("phone", "abc", "name", "x"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("phone", "ABC").containsEntry("name", "x");
  }

  /**
   * #1432: a script that throws is a broken script; the run fails, and the message names which one and where — the
   * exception's kind and the script's line, never its text: {@code toInt()} would quote the cell it could not read.
   */
  @Test
  void a_script_that_throws_fails_naming_the_script_and_its_line_without_the_value() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/throws.kts
        """);
    Map<String, Object> row = new HashMap<>(Map.of("quantity", "env. 4 kg"));
    assertThatThrownBy(() -> hook.apply(row, params, new MappingState()))
        .as("an authored run failure: the run stores this message without the cause's (#1434)")
        .isInstanceOf(ScriptFailedException.class)
        .hasMessage("kotlin hook classpath:mapping/throws.kts failed: NumberFormatException at line 4")
        .hasCauseInstanceOf(NumberFormatException.class);
  }

  /** #1432: on-error reads a hook's `column:`; a script can write several cells, so a kotlin hook declares none. */
  @Test
  void a_column_on_a_kotlin_hook_is_refused() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/read-quantity.kts
        column: quantity
        """);
    assertThatThrownBy(() -> hook.validateParams(params))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'column'");
  }

  /**
   * #1440: context reached the script as text — a list or a map as an empty string, so a closure day the integrator
   * declared vanished. It carries the YAML's own types now.
   */
  @Test
  void context_values_reach_the_script_with_their_own_types() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/context-types.kts
        context:
          country: FR
          lead_days: 3
          closed: ["2026-05-14", "2026-08-14"]
          express: true
          warehouse: { zone: north }
        """);
    Map<String, Object> row = new HashMap<>(Map.of("order_no", "CMD-1"));
    hook.apply(row, params, new MappingState());
    assertThat(row).containsEntry("lead", 4).containsEntry("closed", "2026-05-14,2026-08-14")
        .containsEntry("express", true).containsEntry("zone", "north").containsEntry("country", "FR");
  }

  /** #1440: a context that is not a map would reach the script empty; refused when the manifest is checked. */
  @Test
  void a_context_that_is_not_a_map_is_refused() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/context-types.kts
        context: [FR, 3]
        """);
    assertThatThrownBy(() -> hook.validateParams(params))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'context'");
  }

  @Test
  void blank_classpath_resource_is_refused_by_name() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: "classpath:"
        """);
    assertThatThrownBy(() -> hook.validateParams(params))   // once per spec, before any row
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("classpath:")
        .hasMessageContaining("resource path");
  }

  @Test
  void another_scheme_is_refused_once_per_spec() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: file:/tmp/x.kts
        """);
    assertThatThrownBy(() -> hook.validateParams(params))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("classpath:<resource> or hooks:<name>.kts");
  }

  @Test
  void a_script_that_is_not_there_is_refused_before_any_row() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: classpath:mapping/no-such-script.kts
        """);
    assertThatThrownBy(() -> hook.validateParams(params))   // #1402: it used to wait for the first row
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("mapping/no-such-script.kts");
  }
}
