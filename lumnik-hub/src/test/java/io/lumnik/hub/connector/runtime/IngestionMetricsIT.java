package io.lumnik.hub.connector.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IngestionMetricsIT {

  @Inject IngestionMetrics metrics;
  @Inject MeterRegistry registry;

  @Test
  void counters_register_and_increment() {
    metrics.recordRunFinished("SUCCESS");
    metrics.recordDeadLetter("ValidationFailed");

    assertTrue(registry.get("lumnik_runs_total").tag("status", "SUCCESS").counter().count() >= 1.0);
    assertTrue(registry.get("lumnik_dead_letters_total").tag("reason", "ValidationFailed").counter().count() >= 1.0);
  }
}
