package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The type a fused column keeps (#1325), decided from what the sources hold. */
class EntityViewCompilerTypingTest {

  private static final EntityManifest M = new EntityManifest("Entity", "Order", List.of("no"), List.of(
      new EntityManifest.Source("t_erp", Map.of("no", "no", "d", "d")),
      new EntityManifest.Source("t_csv", Map.of("code", "no", "day", "d"))));

  private static EntityViewCompiler.Sources sources(String erp, String csv) {
    Map<String, Map<String, String>> temporal = new HashMap<>();
    if (!erp.isEmpty()) temporal.put("t_erp", Map.of("d", erp));
    if (!csv.isEmpty()) temporal.put("t_csv", Map.of("day", csv));
    return new EntityViewCompiler.Sources(Set.of(), temporal);
  }

  @ParameterizedTest(name = "{0} + {1} -> {2}")
  @CsvSource(nullValues = "text", value = {
      "date,        date,        date",
      "timestamptz, timestamptz, timestamptz",
      "timestamptz, date,        timestamptz",   // an ERP DATE-as-timestamp beside a parsed CSV day
      "timestamp,   date,        timestamptz",   // a naive timestamp: wall clock read as UTC
      "timestamptz, '',          text",          // one source text: a written date is not guessed
      "'',          '',          text"})
  void theSourcesDecide(String erp, String csv, String fused) {
    assertThat(EntityViewCompiler.fusedType(M, sources(erp, csv), "d")).isEqualTo(fused);
  }

  @Test
  void aSourceThatDoesNotMapTheColumnHasNoVote() {
    EntityManifest m = new EntityManifest("Entity", "Order", List.of("no"), List.of(
        new EntityManifest.Source("t_erp", Map.of("no", "no", "d", "d")),
        new EntityManifest.Source("t_csv", Map.of("code", "no"))));
    assertThat(EntityViewCompiler.fusedType(m, sources("date", ""), "d")).isEqualTo("date");
  }

  /** A date key is typed like any column (#1326 review): the group is the aligned value, its key that value's text. */
  @ParameterizedTest(name = "match key {0} + {1} -> {2}")
  @CsvSource({"date, date, date", "timestamptz, date, timestamptz"})
  void aTemporalMatchKeyIsTypedToo(String erp, String csv, String fused) {
    Map<String, Map<String, String>> temporal = Map.of("t_erp", Map.of("no", erp), "t_csv", Map.of("code", csv));
    EntityViewCompiler.Sources sources = new EntityViewCompiler.Sources(Set.of(), temporal);
    assertThat(EntityViewCompiler.fusedType(M, sources, "no")).isEqualTo(fused);
  }

  @Test
  void eachSourceSaysItsMatchKeyAsTheViewGroupsIt() {
    Map<String, Map<String, String>> temporal = Map.of("t_erp", Map.of("no", "timestamptz"), "t_csv", Map.of("code", "date"));
    EntityViewCompiler.Sources sources = new EntityViewCompiler.Sources(Set.of(), temporal);
    assertThat(EntityViewCompiler.matchKeyExpressions(M, sources, "t_erp")).containsExactly("\"no\"");
    assertThat(EntityViewCompiler.matchKeyExpressions(M, sources, "t_csv"))
        .containsExactly("(\"code\"::timestamp AT TIME ZONE 'UTC')");
  }

  @Test
  void aDateBesideATimestampIsItsMidnightUtc_neverTheSessionsZone() {
    String sql = String.join("\n", new EntityViewCompiler().buildStatements(M, sources("timestamptz", "date")));
    assertThat(sql).contains("(\"day\"::timestamp AT TIME ZONE 'UTC') AS \"d\"")
        .contains("\"d\" AS \"d\"")
        .doesNotContain("\"d\"::text AS \"d\"");
  }

  /** #1327: a day beside times is compared at the day; the times among themselves stay instants. */
  @Test
  void aDayBesideATimeDisagreesOnlyOnAnotherDay() {
    String mixed = String.join("\n", new EntityViewCompiler().buildStatements(M, sources("timestamptz", "date")));
    assertThat(mixed).contains("count(DISTINCT \"d\") FILTER (WHERE _ord IN (0)) > 1"
        + " OR count(DISTINCT (\"d\" AT TIME ZONE 'UTC')::date) > 1");
    String instants = String.join("\n", new EntityViewCompiler().buildStatements(M, sources("timestamptz", "timestamptz")));
    assertThat(instants).contains("CASE WHEN count(DISTINCT \"d\") > 1 THEN").doesNotContain("::date) > 1");
  }
}
