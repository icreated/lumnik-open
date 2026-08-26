package io.lumnik.hub.connector.jdbc;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisabledIfSystemProperty(named = "ollama", matches = "off")
class TemplateGeneratorLlmIT {

  @Inject TemplateGenerator gen;

  @Test
  void llm_first_produces_template_with_at_least_one_placeholder() {
    TableDescriptor td = new TableDescriptor("public", "c_bpartner",
        List.of("c_bpartner_id"),
        List.of(new ColumnDescriptor("c_bpartner_id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 120, false),
                new ColumnDescriptor("description", "VARCHAR", 255, true)),
        List.of(), Optional.of("updated"), 0L);
    List<Map<String, Object>> samples = List.of(
        Map.of("c_bpartner_id", 1, "name", "Acme Corp", "description", "Strategic account"),
        Map.of("c_bpartner_id", 2, "name", "Beta Industries", "description", "Pilot"));
    String template = gen.generate(td, samples, TemplateGenerator.Mode.LLM_FIRST);
    assertNotNull(template);
    assertFalse(template.isBlank());
    assertTrue(template.contains("${"), "template should reference at least one column");
  }

  @Test
  void llm_falls_back_to_heuristic_when_no_samples() {
    TableDescriptor td = new TableDescriptor("public", "ref",
        List.of("id"),
        List.of(new ColumnDescriptor("id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 60, false)),
        List.of(), Optional.empty(), 0L);
    // No sample rows — should fall back to heuristic deterministically
    String template = gen.generate(td, List.of(), TemplateGenerator.Mode.LLM_FIRST);
    assertNotNull(template);
    assertTrue(template.contains("${name}") || template.contains("${id}"));
  }
}
