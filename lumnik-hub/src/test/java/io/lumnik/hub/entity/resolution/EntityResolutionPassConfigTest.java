package io.lumnik.hub.entity.resolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code lumnik.entity.resolution.batch-size} is the pass's page step (#904) — a misconfigured
 * {@code 0} would never advance past the first page, and a negative value fails mid-pass inside the
 * async {@link EntityResolutionRunHook}, where it is hard to diagnose. Same
 * posture as {@link FingerprintHmacKeyTest}: a plain unit test against the {@code @PostConstruct}
 * validation, not a {@code @QuarkusTest} boot.
 */
class EntityResolutionPassConfigTest {

  @Test
  void rejects_a_zero_batch_size() {
    var pass = new EntityResolutionPass();
    pass.batchSize = 0;
    assertThrows(IllegalStateException.class, pass::validateConfig);
  }

  @Test
  void rejects_a_negative_batch_size() {
    var pass = new EntityResolutionPass();
    pass.batchSize = -1;
    assertThrows(IllegalStateException.class, pass::validateConfig);
  }

  @Test
  void accepts_a_positive_batch_size() {
    var pass = new EntityResolutionPass();
    pass.batchSize = 500;
    assertDoesNotThrow(pass::validateConfig);
  }
}
