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
  void non_classpath_script_is_refused_once_per_spec() throws Exception {
    var hook = catalog.cell("kotlin");
    var params = yaml("""
        script: file:/tmp/x.kts
        """);
    assertThatThrownBy(() -> hook.validateParams(params))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("only classpath: scripts supported");
  }
}
