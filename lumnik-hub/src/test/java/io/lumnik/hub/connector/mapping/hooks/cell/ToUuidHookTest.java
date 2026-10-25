package io.lumnik.hub.connector.mapping.hooks.cell;

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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code to-uuid}'s two independent questions, which used to be answered by one knob.
 *
 * <p>{@code generate} fills an ABSENCE — that is its documented job. It also used to decide what
 * happened to a value that was PRESENT but unreadable: with {@code generate: true} such a value
 * was replaced by a freshly minted random UUID. That is a fabricated identifier, unjoinable to
 * every other system holding the real one and indistinguishable downstream from an id the source
 * actually sent — and nothing counted it, so no report could show it had happened.
 *
 * <p>The two questions are now two knobs. {@code generate} still fills an absence.
 * {@code on-invalid} decides the present-but-unreadable case, and defaults to {@code null}: no
 * invention unless the integrator asks for one, in the shape {@code lookup}'s {@code on-missing}
 * already uses.
 *
 */
@QuarkusTest
class ToUuidHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static final String VALID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
  private static final String UUID_SHAPE =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private HashMap<String, Object> rowWith(Object id) {
    var row = new HashMap<String, Object>();
    row.put("customer_id", id);
    return row;
  }

  /** Every outcome whose result is a fixed value. The two minting outcomes follow, below. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void the_cell_and_the_count_follow_the_two_knobs(
      String label, Object input, String paramsYaml, Object expected, long expectedCellErrors)
      throws Exception {
    JsonNode params = YAML.readTree(paramsYaml);
    var row = rowWith(input);
    var state = new MappingState();

    catalog.cell("to-uuid").apply(row, params, state);

    assertThat(row.get("customer_id")).as("the stored id").isEqualTo(expected);
    assertThat(state.counters().cellErrors().get())
        .as("null and generate count because the outcome is a FAILURE the operator must see; keep "
            + "does not, because it is the outcome the INTEGRATOR declared")
        .isEqualTo(expectedCellErrors);
  }

  static Stream<Arguments> cases() {
    String plain = "column: customer_id\n";
    String withGenerate = "column: customer_id\ngenerate: true\n";
    return Stream.of(
        // --- a well-formed id is never touched, whatever the knobs say ---
        Arguments.of("a valid id passes through", VALID, plain, VALID, 0L),
        Arguments.of("a valid id passes through with both knobs set", VALID,
            withGenerate + "on-invalid: generate\n", VALID, 0L),
        // --- an ABSENT cell: generate's own job ---
        Arguments.of("an absent id stays absent without generate", null, plain, null, 0L),
        // --- a PRESENT but unreadable id: on-invalid's job, and generate must not speak for it ---
        Arguments.of("unreadable, default on-invalid: nulled and counted", "N/A", plain, null, 1L),
        Arguments.of("unreadable: generate no longer decides this case", "N/A", withGenerate,
            null, 1L),
        Arguments.of("unreadable, on-invalid keep: left exactly as it arrived, NOT counted",
            "N/A", plain + "on-invalid: keep\n", "N/A", 0L),
        Arguments.of("unreadable, on-invalid null stated explicitly", "N/A",
            plain + "on-invalid: null\n", null, 1L));
  }

  @Test
  void generate_mints_for_an_absent_cell_and_that_is_not_an_error() throws Exception {
    var row = rowWith(null);
    var state = new MappingState();

    catalog.cell("to-uuid").apply(row, YAML.readTree("column: customer_id\ngenerate: true\n"), state);

    assertThat((String) row.get("customer_id"))
        .as("an absent cell is filled with a well-formed id").matches(UUID_SHAPE);
    assertThat(state.counters().cellErrors().get())
        .as("filling a declared absence is the hook doing its job, not failing at it").isZero();
  }

  @Test
  void on_invalid_generate_mints_over_an_unreadable_id_and_counts_it() throws Exception {
    var row = rowWith("N/A");
    var state = new MappingState();

    catalog.cell("to-uuid").apply(row,
        YAML.readTree("column: customer_id\non-invalid: generate\n"), state);

    assertThat((String) row.get("customer_id"))
        .as("the integrator asked for a minted id, so they get one").matches(UUID_SHAPE);
    assertThat(state.counters().cellErrors().get())
        .as("but a minted id REPLACED a value the source really sent — the count is the only "
            + "place that fact survives")
        .isEqualTo(1L);
  }

  @Test
  void an_unknown_on_invalid_mode_is_refused_by_name() throws Exception {
    JsonNode params = YAML.readTree("column: customer_id\non-invalid: pretend\n");

    assertThatThrownBy(() -> catalog.cell("to-uuid").apply(rowWith("N/A"), params, new MappingState()))
        .as("a misspelt mode must be refused, not silently treated as one of the real ones")
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("on-invalid");
  }

  /** #1468: a list or a map on-invalid read as {@code ""} and was refused as {@code got ''}; its shape is named. */
  @Test
  void a_list_or_map_on_invalid_is_refused_naming_its_shape() throws Exception {
    assertThatThrownBy(() -> catalog.cell("to-uuid").validateParams(
        YAML.readTree("column: customer_id\non-invalid: [keep]\n")))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("to-uuid hook: 'on-invalid' must be text, a number or a boolean (got array)");
    assertThatThrownBy(() -> catalog.cell("to-uuid").validateParams(
        YAML.readTree("column: customer_id\non-invalid: { mode: keep }\n")))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("to-uuid hook: 'on-invalid' must be text, a number or a boolean (got object)");
  }

  /**
   * #1423: {@code generate} minted a random id, a new one on every run — the next night's export gave the same
   * supplier another id, and the run reported its row removed. {@code generate-from} derives the id from a column.
   */
  @Test
  void generate_from_mints_the_same_id_for_the_same_value_on_every_run() throws Exception {
    JsonNode params = YAML.readTree("column: ext_id\ngenerate-from: code\non-invalid: generate");
    var hook = catalog.cell("to-uuid");
    var night1 = new HashMap<String, Object>();
    night1.put("code", "F0004");
    night1.put("ext_id", null);
    var night2 = new HashMap<String, Object>(night1);
    var other = new HashMap<String, Object>(night1);
    other.put("code", "F0007");
    var junk = new HashMap<String, Object>(night1);
    junk.put("ext_id", "N/A");
    var state = new MappingState();

    hook.apply(night1, params, state);
    hook.apply(night2, params, state);
    hook.apply(other, params, state);
    hook.apply(junk, params, state);

    assertThat((String) night1.get("ext_id")).matches(UUID_SHAPE).isEqualTo(night2.get("ext_id"))
        .isNotEqualTo(other.get("ext_id")).isEqualTo(junk.get("ext_id"));
    assertThat(state.counters().cellErrors().get()).as("only the junk value counts").isEqualTo(1);
  }

  @Test
  void generate_from_an_empty_column_mints_nothing_and_counts() throws Exception {
    JsonNode params = YAML.readTree("column: ext_id\ngenerate-from: code");
    var row = new HashMap<String, Object>();
    row.put("code", " ");
    row.put("ext_id", null);
    var state = new MappingState();

    catalog.cell("to-uuid").apply(row, params, state);

    assertThat(row.get("ext_id")).isNull();
    assertThat(state.counters().cellErrors().get()).isEqualTo(1);
  }

  @Test
  void generate_from_must_name_another_column() {
    var hook = catalog.cell("to-uuid");
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: ext_id\ngenerate-from: ext_id")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'generate-from'");
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: ext_id\ngenerate-from: [code]")))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'generate-from'");
  }
}
