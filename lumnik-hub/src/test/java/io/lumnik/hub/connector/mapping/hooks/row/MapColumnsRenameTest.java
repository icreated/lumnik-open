package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rename shapes a manifest is refused for, and what a rename does to the row.
 *
 * <p>Why this file did not exist: the same rules were enforced on the JDBC side only, where the
 * hub assembles the rename itself. The csv door reaches this hook through {@code spec.mapping},
 * where the integrator writes it by hand — see {@link MapColumnsHook}'s javadoc for which door
 * refuses when, and for the csv apply-time gap that is still open.
 */
@QuarkusTest
class MapColumnsRenameTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private Map<String, Object> applyTo(Map<String, Object> row, String paramsYaml, MappingState s)
      throws Exception {
    JsonNode params = YAML.readTree(paramsYaml);
    return catalog.row("map-columns").apply(row, params, s).get(0);
  }

  // ---- the shapes a manifest is refused for ----

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedRenames")
  void a_rename_that_would_lose_a_column_is_refused_by_name(String label, String paramsYaml,
      String messageFragment) throws Exception {
    // Param-shape rules moved to validateParams (run once per spec by the engine, before any row);
    // HookValidateParamsTest proves the engine refuses these end-to-end, even on an empty chunk.
    JsonNode params = YAML.readTree(paramsYaml);

    assertThatThrownBy(() -> catalog.row("map-columns").validateParams(params))
        .as("%s", label)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("map-columns")
        .hasMessageContaining(messageFragment);
  }

  static Stream<Arguments> refusedRenames() {
    return Stream.of(
        // `rename:\n  a:` — a colon with nothing after it. String.valueOf(null) would otherwise
        // produce the literal column name "null", which is itself a valid identifier.
        Arguments.of("a target that is nothing at all", "rename:\n  a:\n",
            "not a rename target"),
        Arguments.of("a target that is not a plain identifier", "rename:\n  a: not a column\n",
            "plain identifier"),
        Arguments.of("a target that is a hub system column", "rename:\n  a: tenant_id\n",
            "system column"),
        Arguments.of("two sources onto one target", "rename:\n  a: z\n  b: z\n",
            "more than one source"),
        // Not a map at all. fields() on an array yields an EMPTY iterator, so every check above
        // ran zero times and the hook silently applied no rename and refused nothing.
        Arguments.of("a rename that is a list", "rename: [a, b]\n", "must be a map"),
        Arguments.of("a rename that is a scalar", "rename: nope\n", "must be a map"));
  }

  @Test
  void renaming_a_system_column_AWAY_is_the_whole_point_and_is_never_refused() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("tenant_id", "from-the-source");
    var out = applyTo(row, "rename:\n  tenant_id: source_tenant\n", new MappingState());

    assertThat(out).containsEntry("source_tenant", "from-the-source").doesNotContainKey("tenant_id");
  }

  // ---- what a rename does to the row ----

  @Test
  void a_renamed_column_keeps_its_place_even_when_its_value_is_null() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("legacy_ref", null);
    row.put("keep_me", 1);
    var state = new MappingState();

    var out = applyTo(row, "rename:\n  legacy_ref: ref\n", state);

    assertThat(out)
        .as("the source key was removed, so the target must appear — a column that exists only "
            + "when its first value is non-null is a column whose DDL depends on the data")
        .containsKey("ref");
    assertThat(out.get("ref")).isNull();
    assertThat(out).doesNotContainKey("legacy_ref");
    assertThat(state.counters().cellErrors().get())
        .as("a null value is not a loss — the column is still there").isZero();
  }

  /**
   * The target is a plain, non-system column — {@code id} would not reach this code at all, since
   * it is one of the seven the hub adds and the refusal above stops it first. (It was the example
   * this test was first written with, and the new guard caught it.)
   */
  @Test
  void a_rename_landing_on_a_column_the_row_already_has_is_counted() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("customer_id", "C1");
    row.put("ref", 42);
    var state = new MappingState();

    var out = applyTo(row, "rename:\n  customer_id: ref\n", state);

    assertThat(out.get("ref"))
        .as("the rename still wins — this change makes the loss VISIBLE, it does not alter it")
        .isEqualTo("C1");
    assertThat(state.counters().cellErrors().get())
        .as("42 is gone and no config check could have known: the collision is with the DATA, not "
            + "with another rename entry. The count is the only place that fact survives")
        .isEqualTo(1L);
  }

  /**
   * The claim under test is one both this hook's javadoc and the docs page made: "dropping happens
   * after any rename so a drop entry can name either the old or the new column name". The second
   * half holds. The first does not, and it is the half an integrator is likelier to write.
   */
  @Test
  void dropping_a_renamed_column_by_its_OLD_name_does_nothing() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("CUST_NM", "Benedict");
    var state = new MappingState();

    var out = applyTo(row, "rename:\n  CUST_NM: name\ndrop: [CUST_NM]\n", state);

    assertThat(out)
        .as("drop runs AFTER rename, so the old key is no longer there to remove — the column "
            + "survives under its new name, which is the opposite of what was asked for")
        .containsEntry("name", "Benedict");
  }

  @Test
  void dropping_a_renamed_column_by_its_NEW_name_works() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("CUST_NM", "Benedict");

    var out = applyTo(row, "rename:\n  CUST_NM: name\ndrop: [name]\n", new MappingState());

    assertThat(out).doesNotContainKey("name").doesNotContainKey("CUST_NM");
  }

  /**
   * The same hole {@code rename} had, on the knob whose whole job is to keep something OUT.
   * {@code drop: internal_flag} instead of {@code drop: [internal_flag]} iterates a scalar, which
   * yields no elements — so the column an integrator meant to exclude lands in the hub, silently.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("badDrops")
  void a_drop_that_cannot_drop_anything_is_refused(String label, String paramsYaml)
      throws Exception {
    JsonNode params = YAML.readTree(paramsYaml);

    assertThatThrownBy(() -> catalog.row("map-columns").validateParams(params))
        .as("%s", label)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("map-columns")
        .hasMessageContaining("drop");
  }

  static Stream<Arguments> badDrops() {
    return Stream.of(
        Arguments.of("a bare column name instead of a list", "drop: internal_flag\n"),
        Arguments.of("a map instead of a list", "drop:\n  internal_flag: true\n"),
        Arguments.of("a list holding something that is not a name", "drop: [[a, b]]\n"));
  }

  /**
   * A knob written with a colon and nothing after it reads as ABSENT, not as an error. That is the
   * house convention rather than this hook's opinion: {@code ConfigDefRunner} computes
   * {@code present = value != null && !value.isNull()} for every declared field of every connector
   * type, and the two knobs either side of the map-columns door in
   * {@code CsvFileConnectorValidator} — {@code schedule} and {@code window} — return on
   * {@code isMissingNode() || isNull()}.
   *
   * <p>A review read the accepting branch as the accept-and-ignore defect this hook's rules exist
   * to remove. It is not the same shape. That defect loses a VALUE nothing can show you
   * afterwards; a rename that did not happen leaves every column exactly where it was, under the
   * name the source sent, visible in the table on the first run.
   *
   * <p>Pinned so that changing the convention breaks HERE and is seen — one repo-wide decision,
   * not fourteen rules quietly making up their own minds.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("emptyKnobs")
  void a_knob_with_nothing_after_the_colon_reads_as_absent(String label, String paramsYaml)
      throws Exception {
    var row = new HashMap<String, Object>();
    row.put("CUST_NM", "Benedict");
    var state = new MappingState();

    var out = applyTo(row, paramsYaml, state);

    assertThat(out)
        .as("%s — the row passes through untouched", label)
        .containsExactlyEntriesOf(Map.of("CUST_NM", "Benedict"));
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  static Stream<Arguments> emptyKnobs() {
    return Stream.of(
        Arguments.of("rename with nothing after the colon", "rename:\n"),
        Arguments.of("drop with nothing after the colon", "drop:\n"));
  }

  @Test
  void drop_still_removes_what_it_names_without_counting_anything() throws Exception {
    var row = new HashMap<String, Object>();
    row.put("internal_flag", "X");
    row.put("keep_me", 1);
    var state = new MappingState();

    var out = applyTo(row, "drop: [internal_flag]\n", state);

    assertThat(out).doesNotContainKey("internal_flag").containsEntry("keep_me", 1);
    assertThat(state.counters().cellErrors().get())
        .as("a declared drop is the integrator's own instruction, not a failure").isZero();
  }
}
