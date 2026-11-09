package io.lumnik.hub.connector.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A run's failure text carries what failed, and what failed may be a NUL byte: the row must stay storable (#1855). */
class ConnectorRunTextTest {

  @Test
  void a_failure_text_never_holds_a_nul_byte() {
    var run = new ConnectorRun();

    run.setErrorMessage("bad \u0000 value");
    run.setErrorStack("ERROR: invalid byte sequence for encoding \"UTF8\": 0x00\n\u0000");

    assertThat(run.getErrorMessage()).isEqualTo("bad \uFFFD value");
    assertThat(run.getErrorStack()).doesNotContain("\u0000");
  }
}
