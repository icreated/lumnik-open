package io.lumnik.hub.connector.mapping.hooks.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** parse-coordinates on a row (#1966): what it writes, what it counts, what it refuses before any row. */
class ParseCoordinatesHookTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final String FRANCE = """
      lat: latitude
      lon: longitude
      within: [41.0, -5.5, 51.5, 10.0]
      swapped: fix
      reason: coord_issue
      """;

  private final ParseCoordinatesHook hook = new ParseCoordinatesHook();
  private final MappingState state = new MappingState();

  private static JsonNode yaml(String src) {
    try {
      return YAML.readTree(src);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> applied(String params, Object lat, Object lon) {
    Map<String, Object> row = new HashMap<>();
    row.put("site", "S01");
    row.put("latitude", lat);
    row.put("longitude", lon);
    hook.apply(row, yaml(params), state);
    return row;
  }

  private long cellErrors() {
    return state.counters().cellErrors().get();
  }

  @Test
  void a_readable_pair_lands_as_two_numbers() {
    Map<String, Object> row = applied(FRANCE, "48°50'12\"N", "2°22'58\"E");
    assertThat(row).containsEntry("latitude", new BigDecimal("48.836667"))
        .containsEntry("longitude", new BigDecimal("2.382778")).containsEntry("coord_issue", null)
        .containsEntry("site", "S01");
    assertThat(cellErrors()).isZero();
  }

  @Test
  void a_pair_that_is_already_numbers_is_read_too() {
    Map<String, Object> row = applied(FRANCE, 45.7302, new BigDecimal("4.8309"));
    assertThat(row).containsEntry("latitude", new BigDecimal("45.7302"))
        .containsEntry("longitude", new BigDecimal("4.8309"));
    assertThat(cellErrors()).isZero();
  }

  @Test
  void a_small_number_is_read_in_full_not_in_scientific_notation() {
    Map<String, Object> row = applied("{ lat: latitude, lon: longitude }", 45.0, 0.0001);   // Double prints 1.0E-4
    assertThat(row).containsEntry("latitude", new BigDecimal("45"))
        .containsEntry("longitude", new BigDecimal("0.0001"));
    assertThat(cellErrors()).isZero();
  }

  @Test
  void a_swapped_pair_is_exchanged_and_says_so_without_counting() {
    Map<String, Object> row = applied(FRANCE, "5.3698", "43.2965");
    assertThat(row).containsEntry("latitude", new BigDecimal("43.2965"))
        .containsEntry("longitude", new BigDecimal("5.3698")).containsEntry("coord_issue", "swapped: exchanged");
    assertThat(cellErrors()).isZero();
  }

  @Test
  void an_unreadable_pair_empties_both_cells_says_why_and_counts_once() {
    Map<String, Object> row = applied(FRANCE, "574357.82", "6279642.97");
    assertThat(row).containsEntry("latitude", null).containsEntry("longitude", null)
        .containsEntry("coord_issue", "not degrees");
    assertThat(cellErrors()).isEqualTo(1);
  }

  @Test
  void a_blank_pair_stays_empty_and_counts_nothing() {
    Map<String, Object> row = applied(FRANCE, "", " ");
    assertThat(row).containsEntry("latitude", null).containsEntry("longitude", null).containsEntry("coord_issue", null);
    assertThat(cellErrors()).isZero();
  }

  @Test
  void a_fine_pair_leaves_the_reason_another_pair_wrote() {
    Map<String, Object> row = new HashMap<>();
    row.put("latitude", "45.7302");
    row.put("longitude", "4.8309");
    row.put("coord_issue", "outside the declared area");   // the pickup pair's, a hook earlier
    hook.apply(row, yaml(FRANCE), state);
    assertThat(row).containsEntry("coord_issue", "outside the declared area");
  }

  @Test
  void a_blank_pair_adds_no_column_the_row_did_not_have() {
    Map<String, Object> row = new HashMap<>(Map.of("site", "S09"));
    hook.apply(row, yaml("{ lat: latitude, lon: longitude }"), state);
    assertThat(row).containsOnlyKeys("site");
    assertThat(cellErrors()).isZero();
  }

  @Test
  void a_number_that_is_no_number_is_set_aside_not_thrown() {
    Map<String, Object> row = applied(FRANCE, Double.NaN, Double.POSITIVE_INFINITY);
    assertThat(row).containsEntry("latitude", null).containsEntry("longitude", null)
        .containsEntry("coord_issue", "not a coordinate");
    assertThat(cellErrors()).isEqualTo(1);
  }

  @Test
  void without_reason_no_column_is_added() {
    Map<String, Object> row = applied("{ lat: latitude, lon: longitude }", "n/a", "4.83");
    assertThat(row).containsOnlyKeys("site", "latitude", "longitude").containsEntry("latitude", null)
        .containsEntry("longitude", null);
    assertThat(cellErrors()).isEqualTo(1);
  }

  @Test
  void without_swapped_fix_a_swapped_pair_is_set_aside() {
    Map<String, Object> row = applied("{ lat: latitude, lon: longitude, within: [41, -5.5, 51.5, 10], reason: why }",
        "5.3698", "43.2965");
    assertThat(row).containsEntry("latitude", null).containsEntry("longitude", null).containsEntry("why", "swapped");
    assertThat(cellErrors()).isEqualTo(1);
  }

  @Test
  void a_well_formed_declaration_is_accepted() {
    assertThatCode(() -> hook.validateParams(yaml(FRANCE))).doesNotThrowAnyException();
    assertThatCode(() -> hook.validateParams(yaml("{ lat: a, lon: b }"))).doesNotThrowAnyException();
  }

  static Stream<Arguments> malformedDeclarations() {
    String fourNumbers = "'within' must be four numbers: south, west, north, east";
    return Stream.of(
        Arguments.of("{ lon: b }", "'lat'"),
        Arguments.of("{ lat: a }", "'lon'"),
        Arguments.of("{ lat: a, lon: ' ' }", "'lon' must name a column"),
        Arguments.of("{ lat: a, lon: a }", "'lat' and 'lon' must name two different columns"),
        Arguments.of("{ lat: a, lon: b, reason: a }", "'reason' must name a column of its own"),
        Arguments.of("{ lat: a, lon: b, reason: '' }", "'reason' must name a column"),
        Arguments.of("{ lat: a, lon: b, swapped: fixx }", "'swapped' must be \"fix\""),
        Arguments.of("{ lat: a, lon: b, swapped: true }", "'swapped'"),
        Arguments.of("{ lat: a, lon: b, within: 41 }", fourNumbers),
        Arguments.of("{ lat: a, lon: b, within: [41, -5.5, 51.5] }", fourNumbers),
        Arguments.of("{ lat: a, lon: b, within: [41, -5, 51, 10, 1] }", fourNumbers),
        Arguments.of("{ lat: a, lon: b, within: [41, west, 51, 10] }", fourNumbers),
        Arguments.of("{ lat: a, lon: b, within: ['41', -5, 51, 10] }", fourNumbers),
        Arguments.of("{ lat: a, lon: b, within: [41, -5, 91, 10] }", "'within': a latitude is between -90 and 90"),
        Arguments.of("{ lat: a, lon: b, within: [41, -181, 51, 10] }", "'within': a longitude is between -180 and 180"),
        Arguments.of("{ lat: a, lon: b, within: [51, -5, 41, 10] }", "'within': south must be below north"),
        Arguments.of("{ lat: a, lon: b, within: [41, -5, 41, 10] }", "'within': south must be below north"),
        Arguments.of("{ lat: a, lon: b, within: [41, 170, 51, -170] }", "'within': west must be below east — an area across the antimeridian is not supported"));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("malformedDeclarations")
  void a_malformed_declaration_is_refused_before_any_row(String params, String fragment) {
    assertThatThrownBy(() -> hook.validateParams(yaml(params)))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageStartingWith("parse-coordinates hook: ").hasMessageContaining(fragment);
  }

  @Test
  void it_declares_the_columns_it_reads_and_the_one_it_adds() {
    assertThat(hook.params()).containsExactlyInAnyOrder("lat", "lon", "within", "swapped", "reason");
    assertThat(hook.columns(yaml(FRANCE)).steps()).isEqualTo(List.of(
        new ColumnEffect.Read("lat", "latitude"), new ColumnEffect.Read("lon", "longitude"),
        new ColumnEffect.Add("coord_issue")));
    assertThat(hook.columns(yaml("{ lat: a, lon: b }")).steps()).isEqualTo(List.of(
        new ColumnEffect.Read("lat", "a"), new ColumnEffect.Read("lon", "b")));
  }
}
