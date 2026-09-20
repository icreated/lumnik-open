package io.lumnik.hub.connector.mapping.writer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DriftDetectorIT {

  @Inject DriftDetector detector;

  @Test
  void detects_missing_column() {
    // Test against a known table — use connector.dynamic_table itself
    var warnings = detector.detect("connector", "dynamic_table",
        Map.of("tenant_id", "BIGINT", "mapping_id", "TEXT", "nonexistent_col", "TEXT"));
    assertThat(warnings).anySatisfy(w -> assertThat(w).contains("nonexistent_col"));
  }

  @Test
  void empty_warnings_when_schema_matches() {
    var warnings = detector.detect("connector", "dynamic_table",
        Map.of("mapping_id", "TEXT"));
    assertThat(warnings).isEmpty();
  }

  /**
   * #525 — the physical truth {@code DynamicTableWriter.createSchema} reconciles against instead
   * of a chunk's own fresh inference. Types must come back in the same DDL vocabulary
   * {@code SchemaInferrer} emits, not {@code information_schema}'s own spelling.
   */
  @Test
  void actualSchema_translatesPhysicalTypesIntoTheRegistrysDdlVocabulary() {
    var actual = detector.actualSchema("connector", "dynamic_table");

    assertThat(actual).isNotNull();
    assertThat(actual.get("table_name")).isEqualTo("TEXT");
    assertThat(actual.get("tenant_id")).isEqualTo("BIGINT");
    assertThat(actual.get("schema_inferred")).isEqualTo("JSONB");
    assertThat(actual.get("created_at")).isEqualTo("TIMESTAMPTZ");
  }

  @Test
  void actualSchema_isNullWhenTheTableDoesNotExist() {
    assertThat(detector.actualSchema("connector", "table_that_does_not_exist_525")).isNull();
  }
}
