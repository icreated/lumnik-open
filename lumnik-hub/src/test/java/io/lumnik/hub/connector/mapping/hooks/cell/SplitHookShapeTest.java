package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code split} does when the value does NOT have the shape {@code into} declares.
 *
 * <p>{@code CellHooksBasicTest} covers the case where it does — two names, two parts. The
 * documented contract (transformers.md) declares {@code column}, {@code by}, {@code into} and that
 * the parts are trimmed; it says nothing about a mismatch, and the hook answered every mismatch in
 * silence: surplus parts were dropped by a {@code i < names.size() && i < parts.length} loop, a
 * shortfall left the declared column uncreated, and {@code String.split} without a limit deleted
 * trailing empty parts before either could be seen.
 *
 * <p>The channel here is {@code cellErrors}, not the DLQ, and deliberately: this is the per-VALUE
 * tier, and its established shape is {@code ParseDateHook} — a soft failure that writes the row and
 * counts the cell. Quarantining the row would be a different, louder contract than any other cell
 * hook has. The count surfaces as {@code cell_errors} on {@code lm run get}, which the connector
 * docs already tell an operator to read when a mapped column comes back blank.
 */
@QuarkusTest
class SplitHookShapeTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void a_split_that_does_not_match_what_into_declares_is_counted(
      String label, String paramsYaml, String value,
      Map<String, Object> expectedEntries, long expectedCellErrors) throws Exception {
    JsonNode params = YAML.readTree(paramsYaml);
    var row = new HashMap<String, Object>();
    row.put("composite", value);
    var state = new MappingState();

    catalog.cell("split").apply(row, params, state);

    expectedEntries.forEach((k, v) -> assertThat(row)
        .as("column '%s' — a name `into` declares must exist, even when no part filled it", k)
        .containsEntry(k, v));
    assertThat(state.counters().cellErrors().get())
        .as("a value whose part count does not match `into` is a soft failure and must be COUNTED "
            + "— silence is what let a dropped field look like a written one")
        .isEqualTo(expectedCellErrors);
  }

  static Stream<Arguments> shapes() {
    String twoNames = "column: composite\nby: ','\ninto: [x, y]\n";
    String fourNames = "column: composite\nby: ','\ninto: [p, q, r, s]\n";
    String threeNames = "column: composite\nby: ','\ninto: [x, y, z]\n";
    return Stream.of(
        Arguments.of("exact fit is silent", twoNames, "A,B",
            expected("x", "A", "y", "B"), 0L),
        // An empty part BETWEEN separators already survived: only the trailing ones were deleted,
        // by String.split's zero-limit default. Both are parts.
        Arguments.of("an empty part between separators is a part", threeNames, "A,,C",
            expected("x", "A", "y", "", "z", "C"), 0L),
        Arguments.of("trailing empty parts fill their declared columns", fourNames, "A,B,,",
            expected("p", "A", "q", "B", "r", "", "s", ""), 0L),
        Arguments.of("surplus parts are counted, not swallowed", twoNames, "A,B,C",
            expected("x", "A", "y", "B"), 1L),
        Arguments.of("a shortfall still creates the declared column, and counts", threeNames, "A",
            expected("x", "A", "y", null, "z", null), 1L));
  }

  /**
   * {@code into-array} has its own defect on the same line: the surviving parts REPLACE the source
   * cell, so a trailing empty deleted by {@code String.split} is destroyed rather than merely
   * unmapped — the composite it came from is gone too.
   */
  @Test
  void into_array_keeps_trailing_empty_parts() throws Exception {
    JsonNode params = YAML.readTree("column: composite\nby: ','\ninto-array: true\n");
    var row = new HashMap<String, Object>();
    row.put("composite", "A,B,,");
    var state = new MappingState();

    catalog.cell("split").apply(row, params, state);

    assertThat(row.get("composite"))
        .as("the array replaces the cell, so a dropped trailing part is destroyed, not deferred")
        .isEqualTo(List.of("A", "B", "", ""));
    assertThat(state.counters().cellErrors().get())
        .as("into-array declares no arity, so nothing mismatched").isZero();
  }

  /** Small helper: {@code Map.of} refuses null values, and a declared-but-unfilled column IS null. */
  private static Map<String, Object> expected(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}
