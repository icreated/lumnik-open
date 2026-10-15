package io.lumnik.hub.entity.resolution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PassSummary} tallied with {@code long}/{@code int} fields — a full replay on a large
 * tenant (or several sources over a long ingestion history) can process more than {@link
 * Integer#MAX_VALUE} rows over time, which would silently wrap to a negative count and misreport
 * an operator-visible outcome (the REST door's {@code ResolveResult} included). Pinned directly
 * against the counter arithmetic rather than actually processing billions of rows.
 *
 * <p>#622 extended the tally with the observability counters {@link ResolutionResult} now carries
 * — {@code newlyMinted}, {@code providerCalls}/{@code providerFailures}, and {@code ruleHits}
 * (the per-rule distribution a whole pass folds {@code ResolutionResult.ruleIdsInvolved()} into).
 */
class EntityResolutionPassSummaryTest {

  private static ResolutionResult resolved(boolean newlyMinted, List<String> ruleIds) {
    return new ResolutionResult(EntityResolutionState.RESOLVED, UUID.randomUUID(), newlyMinted, 0, 0, ruleIds);
  }

  @Test
  void counting_past_integer_max_value_does_not_wrap_to_negative() {
    var atTheEdge = new EntityResolutionPass.PassSummary(
        Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, Map.of());

    var oneMore = atTheEdge.counting(resolved(false, List.of()));

    assertThat(oneMore.resolved()).isEqualTo(((long) Integer.MAX_VALUE) + 1);
  }

  @Test
  void plus_past_integer_max_value_does_not_wrap_to_negative() {
    var a = new EntityResolutionPass.PassSummary(Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, Map.of());
    var b = new EntityResolutionPass.PassSummary(1, 0, 0, 0, 0, 0, 0, Map.of());

    assertThat(a.plus(b).resolved()).isEqualTo(((long) Integer.MAX_VALUE) + 1);
  }

  @Test
  void counting_tallies_a_fresh_mint() {
    var total = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(true, List.of()));

    assertThat(total.newlyMinted()).isEqualTo(1);
  }

  @Test
  void counting_tallies_provider_calls_and_failures() {
    var call = new ResolutionResult(EntityResolutionState.UNRESOLVED_PROVIDER, null, false, 1, 1, List.of());

    var total = EntityResolutionPass.PassSummary.EMPTY.counting(call);

    assertThat(total.providerCalls()).isEqualTo(1);
    assertThat(total.providerFailures()).isEqualTo(1);
  }

  @Test
  void counting_tallies_every_rule_id_involved_in_one_call() {
    var total = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email", "phone")));

    assertThat(total.ruleHits()).isEqualTo(Map.of("email", 1L, "phone", 1L));
  }

  @Test
  void counting_accumulates_the_same_rule_id_across_several_calls() {
    var total = EntityResolutionPass.PassSummary.EMPTY
        .counting(resolved(false, List.of("email")))
        .counting(resolved(false, List.of("email")));

    assertThat(total.ruleHits()).isEqualTo(Map.of("email", 2L));
  }

  @Test
  void plus_merges_rule_hits_from_both_sides() {
    var a = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email")));
    var b = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email", "phone")));

    assertThat(a.plus(b).ruleHits()).isEqualTo(Map.of("email", 2L, "phone", 1L));
  }

  // ---------------------------------------------------------------------------------------------
  // Copilot review on PR #645: counting()/plus() copied the whole ruleHits map on every call,
  // even when there was nothing new to fold in — real cost over a replay's per-row loop. Neither
  // fix may MUTATE a map a caller could still be holding (a record's accessor returning a
  // different value after some unrelated call would itself be a correctness bug) — only skip the
  // copy when the side being folded in contributes nothing, so the reference is passed along
  // unchanged rather than rebuilt.
  // ---------------------------------------------------------------------------------------------

  @Test
  void counting_does_not_copy_ruleHits_when_the_row_names_no_rule() {
    var withHits = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email")));

    var after = withHits.counting(resolved(false, List.of()));

    assertThat(after.ruleHits()).isSameAs(withHits.ruleHits());
  }

  @Test
  void counting_never_mutates_the_map_a_caller_still_holds() {
    var withHits = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email")));
    Map<String, Long> before = Map.copyOf(withHits.ruleHits());

    withHits.counting(resolved(false, List.of("email")));

    assertThat(withHits.ruleHits()).isEqualTo(before);
  }

  @Test
  void plus_does_not_copy_ruleHits_when_the_other_side_is_empty() {
    var withHits = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email")));

    var after = withHits.plus(EntityResolutionPass.PassSummary.EMPTY);

    assertThat(after.ruleHits()).isSameAs(withHits.ruleHits());
  }

  @Test
  void plus_does_not_copy_ruleHits_when_this_side_is_empty() {
    var withHits = EntityResolutionPass.PassSummary.EMPTY.counting(resolved(false, List.of("email")));

    var after = EntityResolutionPass.PassSummary.EMPTY.plus(withHits);

    assertThat(after.ruleHits()).isSameAs(withHits.ruleHits());
  }
}
