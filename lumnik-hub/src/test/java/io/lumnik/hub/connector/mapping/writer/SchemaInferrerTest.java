package io.lumnik.hub.connector.mapping.writer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaInferrerTest {

  @Inject SchemaInferrer inferrer;

  @Test
  void infers_text_for_string_values() {
    var rows = List.of(
        Map.<String,Object>of("name", "Alice"),
        Map.<String,Object>of("name", "Bob"));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("name")).isEqualTo("TEXT");
  }

  @Test
  void infers_numeric_for_all_numbers() {
    var rows = List.of(
        Map.<String,Object>of("price", new BigDecimal("12.5")),
        Map.<String,Object>of("price", new BigDecimal("99")));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("price")).isEqualTo("NUMERIC(18,4)");
  }

  @Test
  void infers_timestamptz_for_all_instants() {
    var rows = List.of(
        Map.<String,Object>of("createdAt", Instant.now()),
        Map.<String,Object>of("createdAt", Instant.now()));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("createdAt")).isEqualTo("TIMESTAMPTZ");
  }

  @Test
  void infers_date_for_all_local_dates() {
    var rows = List.of(
        Map.<String,Object>of("birth", LocalDate.now()),
        Map.<String,Object>of("birth", LocalDate.now().minusDays(1)));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("birth")).isEqualTo("DATE");
  }

  @Test
  void infers_jsonb_for_lists() {
    var rows = List.of(
        Map.<String,Object>of("tags", List.of("a","b")));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("tags")).isEqualTo("JSONB");
  }

  @Test
  void infers_boolean_for_all_booleans() {
    var rows = List.of(
        Map.<String,Object>of("active", true),
        Map.<String,Object>of("active", false));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("active")).isEqualTo("BOOLEAN");
  }

  @Test
  void falls_back_to_text_on_mixed_types() {
    var rows = List.of(
        Map.<String,Object>of("v", "hello"),
        Map.<String,Object>of("v", 42));
    var schema = inferrer.infer(rows);
    assertThat(schema.get("v")).isEqualTo("TEXT");
  }

  @Test
  void ignores_null_values_when_inferring() {
    var rows = new ArrayList<Map<String,Object>>();
    Map<String,Object> r1 = new HashMap<>(); r1.put("v", null); rows.add(r1);
    Map<String,Object> r2 = Map.of("v", Instant.now()); rows.add(r2);
    var schema = inferrer.infer(rows);
    assertThat(schema.get("v")).isEqualTo("TIMESTAMPTZ");
  }

  @Test
  void empty_rows_returns_empty_schema() {
    assertThat(inferrer.infer(List.of())).isEmpty();
  }

  @Test
  void all_null_column_defaults_to_text() {
    var rows = new ArrayList<Map<String,Object>>();
    Map<String,Object> r1 = new HashMap<>(); r1.put("v", null); rows.add(r1);
    var schema = inferrer.infer(rows);
    assertThat(schema.get("v")).isEqualTo("TEXT");
  }
}
