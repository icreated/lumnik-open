package io.lumnik.hub.connector.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TemplateGeneratorHeuristicTest {

  private final TemplateGenerator gen = new TemplateGenerator();

  @Test
  void heuristic_uses_name_and_description_when_present() {
    TableDescriptor td = new TableDescriptor("public", "c_bpartner",
        List.of("c_bpartner_id"),
        List.of(new ColumnDescriptor("c_bpartner_id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 120, false),
                new ColumnDescriptor("description", "VARCHAR", 255, true)),
        List.of(), Optional.of("updated"), 0L);
    String t = gen.generateHeuristic(td);
    assertTrue(t.contains("${name}"));
    assertTrue(t.contains("${description}"));
    assertTrue(t.contains("${c_bpartner_id}"));
  }

  @Test
  void heuristic_uses_code_and_label_pattern() {
    TableDescriptor td = new TableDescriptor("public", "ref_status",
        List.of("id"),
        List.of(new ColumnDescriptor("id", "BIGINT", 19, false),
                new ColumnDescriptor("code", "VARCHAR", 10, false),
                new ColumnDescriptor("label", "VARCHAR", 60, false)),
        List.of(), Optional.empty(), 0L);
    String t = gen.generateHeuristic(td);
    assertTrue(t.contains("${code}"));
    assertTrue(t.contains("${label}"));
  }

  @Test
  void heuristic_falls_back_to_dump_when_no_human_columns() {
    TableDescriptor td = new TableDescriptor("public", "stats",
        List.of("id"),
        List.of(new ColumnDescriptor("id", "BIGINT", 19, false),
                new ColumnDescriptor("metric_value", "NUMERIC", 12, true),
                new ColumnDescriptor("recorded_at", "TIMESTAMP", 6, false)),
        List.of(), Optional.of("recorded_at"), 0L);
    String t = gen.generateHeuristic(td);
    assertTrue(t.contains("${id}"));
    assertTrue(t.contains("${metric_value}"));
    assertTrue(t.contains("${recorded_at}"));
  }

  @Test
  void heuristic_template_is_non_blank_for_minimal_table() {
    TableDescriptor td = new TableDescriptor("public", "tiny",
        List.of("id"),
        List.of(new ColumnDescriptor("id", "BIGINT", 19, false)),
        List.of(), Optional.empty(), 0L);
    String t = gen.generateHeuristic(td);
    assertNotNull(t);
    assertFalse(t.isBlank());
  }
}
