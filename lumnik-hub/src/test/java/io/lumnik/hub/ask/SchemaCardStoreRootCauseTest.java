package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SchemaCardStoreRootCauseTest {

  @Test
  void the_root_cause_message_is_the_innermost_sentence() {
    var root = new IllegalArgumentException("table name \"\" is not a name");
    assertThat(SchemaCardStore.rootCauseMessage(new RuntimeException("wrapper", root)))
        .isEqualTo("table name \"\" is not a name");
  }

  /** {@code initCause} refuses only self-causation: A→B→A is constructible, and the walk must end on it (#1289). */
  @Test
  void a_cyclic_cause_chain_still_returns() {
    var a = new RuntimeException("a");
    var b = new RuntimeException("b", a);
    a.initCause(b);
    String message = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SchemaCardStore.rootCauseMessage(a));
    assertThat(message).isIn("a", "b");
  }
}
