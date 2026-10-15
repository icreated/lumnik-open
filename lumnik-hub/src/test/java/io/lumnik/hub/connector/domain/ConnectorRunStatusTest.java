package io.lumnik.hub.connector.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorRunStatusTest {

  @Test
  void every_value_round_trips_through_its_sql_spelling() {
    for (ConnectorRunStatus s : ConnectorRunStatus.values()) {
      assertThat(ConnectorRunStatus.fromSql(s.sqlValue())).isEqualTo(s);
    }
  }

  @Test
  void the_spellings_are_the_ones_the_column_has_always_held() {
    assertThat(ConnectorRunStatus.values()).extracting(ConnectorRunStatus::sqlValue)
        .containsExactly("Queued", "Running", "Completed", "Partial", "Failed", "Cancelled");
  }

  @Test
  void neither_queued_nor_running_is_terminal() {
    assertThat(ConnectorRunStatus.QUEUED.isTerminal()).isFalse();
    assertThat(ConnectorRunStatus.RUNNING.isTerminal()).isFalse();
    assertThat(ConnectorRunStatus.FAILED.isTerminal()).isTrue();
  }

  @Test
  void an_unknown_value_is_refused() {
    assertThatThrownBy(() -> ConnectorRunStatus.fromSql("running"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
