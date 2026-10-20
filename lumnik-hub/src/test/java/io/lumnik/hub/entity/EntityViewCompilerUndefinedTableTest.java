package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EntityViewCompilerUndefinedTableTest {

  @Test
  void the_undefined_table_behind_a_wrapper_is_found_first_line_only() {
    var sql = new SQLException("relation \"connector.t_cust\" does not exist\n  Position: 15", "42P01");
    assertThat(EntityViewCompiler.undefinedTableLine(new RuntimeException("wrapper", sql)))
        .isEqualTo("relation \"connector.t_cust\" does not exist");
  }

  @Test
  void another_sqlstate_is_not_an_undefined_table() {
    assertThat(EntityViewCompiler.undefinedTableLine(new SQLException("column does not exist", "42703"))).isNull();
  }

  /** {@code initCause} refuses only self-causation: A→B→A is constructible, and the walk must end on it (#1289). */
  @Test
  void a_cyclic_cause_chain_without_one_still_returns() {
    var a = new RuntimeException("a");
    var b = new RuntimeException("b", a);
    a.initCause(b);
    String line = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> EntityViewCompiler.undefinedTableLine(a));
    assertThat(line).isNull();
  }
}
