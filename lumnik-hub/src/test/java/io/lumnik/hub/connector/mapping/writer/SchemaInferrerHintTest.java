package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests (no Quarkus/Docker) for the source-type-hint behavior that fixes
 * sparse columns mistyped as TEXT when their first batch is all-NULL — the cause of
 * cross-table {@code operator does not exist: numeric = text} on the ask path.
 */
class SchemaInferrerHintTest {

  private final SchemaInferrer inferrer = new SchemaInferrer();

  @Test
  void all_null_column_uses_source_type_hint_when_present() {
    // A sparse FK like c_payment.c_order_id: all NULL in this batch, but source declares numeric.
    var rows = new ArrayList<Map<String, Object>>();
    Map<String, Object> r1 = new HashMap<>(); r1.put("c_order_id", null); rows.add(r1);
    var schema = inferrer.infer(rows, Map.of("c_order_id", "NUMERIC(18,4)"));
    assertThat(schema.get("c_order_id")).isEqualTo("NUMERIC(18,4)");
  }

  @Test
  void all_null_column_without_hint_still_defaults_to_text() {
    var rows = new ArrayList<Map<String, Object>>();
    Map<String, Object> r1 = new HashMap<>(); r1.put("v", null); rows.add(r1);
    var schema = inferrer.infer(rows, Map.of("other", "NUMERIC(18,4)"));
    assertThat(schema.get("v")).isEqualTo("TEXT");
  }

  @Test
  void observed_values_win_over_hint() {
    // Conservative rule: when the sample carries a Java-type signal, never let the hint override it
    // (guards the setObject contract — a hook may have stringified a numeric-looking column).
    var rows = List.of(
        Map.<String, Object>of("v", "char-value"),
        Map.<String, Object>of("v", "another"));
    var schema = inferrer.infer(rows, Map.of("v", "NUMERIC(18,4)"));
    assertThat(schema.get("v")).isEqualTo("TEXT");
  }

  @Test
  void empty_hints_behaves_like_plain_infer() {
    var rows = new ArrayList<Map<String, Object>>();
    Map<String, Object> r1 = new HashMap<>(); r1.put("v", null); rows.add(r1);
    assertThat(inferrer.infer(rows, Map.of()).get("v")).isEqualTo("TEXT");
  }

  @Test
  void pgTypeForSqlType_maps_numerics_temporals_and_falls_back_to_text() {
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.NUMERIC)).isEqualTo("NUMERIC(18,4)");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.DECIMAL)).isEqualTo("NUMERIC(18,4)");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.INTEGER)).isEqualTo("NUMERIC(18,4)");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.BIGINT)).isEqualTo("NUMERIC(18,4)");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.DOUBLE)).isEqualTo("NUMERIC(18,4)");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.TIMESTAMP)).isEqualTo("TIMESTAMPTZ");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.TIMESTAMP_WITH_TIMEZONE)).isEqualTo("TIMESTAMPTZ");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.DATE)).isEqualTo("DATE");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.BOOLEAN)).isEqualTo("BOOLEAN");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.VARCHAR)).isEqualTo("TEXT");
    assertThat(SchemaInferrer.pgTypeForSqlType(Types.OTHER)).isEqualTo("TEXT");
  }
}
