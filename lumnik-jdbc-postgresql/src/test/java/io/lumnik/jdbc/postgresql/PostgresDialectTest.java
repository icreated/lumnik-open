package io.lumnik.jdbc.postgresql;

import io.lumnik.jdbc.spi.ColumnDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PostgresDialectTest {

  private final PostgresDialect d = new PostgresDialect();

  @Test
  void id_is_postgres() {
    assertEquals("postgres", d.id());
  }

  @Test
  void default_skip_patterns_are_exactly_the_three_historical_ones() {
    assertEquals(List.of("pg_*", "information_schema*", "t_*"), d.defaultSkipPatterns());
  }

  @Test
  void quote_identifier_wraps_in_double_quotes() {
    assertEquals("\"c_bpartner\"", d.quoteIdentifier("c_bpartner"));
    assertEquals("\"a.b\"", d.quoteIdentifier("a.b"));
  }

  @Test
  void quote_identifier_escapes_embedded_double_quote() {
    assertEquals("\"weird\"\"name\"", d.quoteIdentifier("weird\"name"));
  }

  @Test
  void quote_identifier_corpus_pins_the_exact_output() {
    assertEquals("\"a\"", d.quoteIdentifier("a"));
    assertEquals("\"A b\"", d.quoteIdentifier("A b"));
    assertEquals("\"select\"", d.quoteIdentifier("select"));
    assertEquals("\"we\"\"ird\"", d.quoteIdentifier("we\"ird"));
    assertEquals("\"\"\"\"", d.quoteIdentifier("\""));
  }

  @Test
  void quote_identifier_rejects_null_or_blank() {
    assertThrows(IllegalArgumentException.class, () -> d.quoteIdentifier(null));
    assertThrows(IllegalArgumentException.class, () -> d.quoteIdentifier(""));
  }

  @Test
  void infer_watermark_picks_updated_at_first() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true),
        new ColumnDescriptor("updated_at", "TIMESTAMP", 6, false),
        new ColumnDescriptor("modified_at", "TIMESTAMP", 6, true));
    assertEquals(Optional.of("updated_at"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_updated_then_modified() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("updated", "TIMESTAMP", 6, false));
    assertEquals(Optional.of("updated"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_any_timestamp() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("created_at", "TIMESTAMP", 6, false));
    assertEquals(Optional.of("created_at"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_numeric_pk_when_no_timestamp() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true));
    assertEquals(Optional.of("id"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_returns_empty_when_pk_is_text() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("code", "VARCHAR", 10, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true));
    assertEquals(Optional.empty(), d.inferWatermarkColumn(cols, List.of("code")));
  }
}
