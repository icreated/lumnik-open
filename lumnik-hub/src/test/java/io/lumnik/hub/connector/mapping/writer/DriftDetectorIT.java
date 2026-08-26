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
}
