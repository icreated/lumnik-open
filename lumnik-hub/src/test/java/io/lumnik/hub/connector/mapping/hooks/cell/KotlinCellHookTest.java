package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
