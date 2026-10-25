package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #1379 review: the shown value and the ledger's first entry come from one order, whatever mixes in the column. */
class EntityFusionOrderTest {

  @Test
  void a_day_beside_a_time_orders_the_ledger_like_the_value_times_first_then_the_preferred_source() {
    // Source 0 holds times, source 1 holds days and is preferred: a day still never hides the hour.
    EntityViewCompiler.Fusion f = new EntityViewCompiler.Fusion("timestamptz", List.of(0), 1, true);
    String order = "ORDER BY _ord NOT IN (0), _ord <> 1, _ord, \"due\"";
    assertThat(f.representative("due")).contains(order);
    assertThat(f.ledger("due")).contains(order);
  }

  @Test
  void without_a_preference_nor_a_day_the_order_is_the_source_order() {
    EntityViewCompiler.Fusion f = new EntityViewCompiler.Fusion(null, null, null, true);
    assertThat(f.representative("city")).contains("ORDER BY _ord, \"city\"");
    assertThat(f.ledger("city")).contains("ORDER BY _ord, \"city\"");
  }

  /** #1449: a column whose sources progress (a stage across flows) declares conflicts: false — it is never compared. */
  @Test
  void a_column_that_is_not_compared_has_no_ledger_and_keeps_its_value_rule() {
    EntityViewCompiler.Fusion f = new EntityViewCompiler.Fusion(null, null, null, false);
    assertThat(f.ledger("stage")).isEqualTo("NULL::jsonb");
    assertThat(f.representative("stage")).contains("ORDER BY _ord, \"stage\"");
  }
}
