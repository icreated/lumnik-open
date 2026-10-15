package io.lumnik.hub.entity.resolution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.cell.ParseDateHook;
import io.lumnik.hub.connector.mapping.hooks.cell.ParseEmailHook;
import io.lumnik.hub.connector.mapping.hooks.cell.ParseNumberHook;
import io.lumnik.hub.connector.mapping.hooks.cell.ParsePhoneHook;
import io.lumnik.hub.connector.mapping.hooks.cell.RegexExtractHook;
import io.lumnik.hub.connector.mapping.hooks.cell.RegexReplaceHook;
import io.lumnik.hub.connector.mapping.hooks.cell.ToUuidHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot #616 establishes the allowlist by test, not by reading the source (design doc §5) — this
 * pins <b>why</b> the seven "admitted if pinned" candidates from the design's provisional table
 * did not make it into {@link NormalizerAllowlist#ADMITTED}. Each is exercised directly against
 * the production {@code CellHook}, with the only params a bare {@code normalize: [id]} entry could
 * ever synthesize (a single {@code column}, nothing else) — never through {@link
 * NormalizationOrchestrator}, which already refuses all seven before this point is reached.
 *
 * <p>No CDI: every hook here has a public no-arg constructor.
 */
class NormalizerAllowlistExclusionEvidenceTest {

  private static final String COLUMN = "value";

  private static String applyColumnHook(CellHook hook, String raw) {
    Map<String, Object> row = new HashMap<>();
    row.put(COLUMN, raw);
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.put("column", COLUMN);
    hook.apply(row, params, new MappingState());
    Object v = row.get(COLUMN);
    return v == null ? null : v.toString();
  }

  /** The four hooks whose default, param-less behaviour nulls out anything it cannot parse — one
   * case table, same shape, same assertion; {@code parse-date}/{@code regex-replace}/{@code
   * regex-extract} below fail the property a different way and stay separate tests. */
  private static Stream<Arguments> hooksThatCollapseUnparsableInputToNull() {
    return Stream.of(
        Arguments.of("parse-email", new ParseEmailHook(), 1L, "a valid email"),
        Arguments.of("parse-phone", new ParsePhoneHook(), 2L, "a parsable phone number"),
        Arguments.of("parse-number", new ParseNumberHook(), 3L, "a parsable number"),
        Arguments.of("to-uuid", new ToUuidHook(), 4L, "a well-formed UUID"));
  }

  @ParameterizedTest(name = "{0} collapses unparsable input onto a null constant")
  @MethodSource("hooksThatCollapseUnparsableInputToNull")
  void collapses_unparsable_input_onto_a_null_constant(
      String hookId, CellHook hook, long seed, String whatARandomLetterStringIsNot) {
    Set<String> outputs = new LinkedHashSet<>();
    for (String s : RandomLetterStrings.of(seed, 100)) outputs.add(applyColumnHook(hook, s));

    assertThat(outputs)
        .as("%s: no random letter string is %s", hookId, whatARandomLetterStringIsNot)
        .containsExactly((String) null);
  }

  @Test
  void parse_date_collapses_every_input_onto_a_null_constant_with_no_formats_to_supply() {
    // normalize: carries no per-hook params, so `formats` (mandatory for this hook to parse
    // anything) can never be supplied — it nulls every row, valid dates included.
    ParseDateHook hook = new ParseDateHook();
    Set<String> outputs = new LinkedHashSet<>();
    outputs.add(applyColumnHook(hook, "2024-01-15"));
    outputs.add(applyColumnHook(hook, "not-a-date"));
    outputs.add(applyColumnHook(hook, "15/01/2024"));

    assertThat(outputs).as("with no formats reachable from normalize:, even valid dates null out")
        .containsExactly((String) null);
  }

  @Test
  void regex_replace_is_an_identity_no_op_with_no_pattern_to_supply() {
    // Not a collapse — the opposite failure: with no `pattern` reachable from normalize:, it does
    // nothing at all, which would silently mislead a manifest author into believing a
    // normalization ran.
    RegexReplaceHook hook = new RegexReplaceHook();
    for (String s : RandomLetterStrings.of(5, 20)) {
      assertThat(applyColumnHook(hook, s)).as("no-op without a configurable pattern").isEqualTo(s);
    }
  }

  @Test
  void regex_extract_writes_to_other_columns_never_the_normalized_field_itself() {
    // Same "changes the row's shape instead of normalizing a scalar" reason `split` is excluded
    // for (design doc §5) — even fully configured, it cannot be a same-column normalizer.
    RegexExtractHook hook = new RegexExtractHook();
    Map<String, Object> row = new HashMap<>();
    row.put(COLUMN, "2024-01-15");
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.put("column", COLUMN);
    params.put("pattern", "(\\d{4})-(\\d{2})-(\\d{2})");
    ObjectNode groups = params.putObject("groups");
    groups.put("1", "year");
    hook.apply(row, params, new MappingState());

    assertThat(row.get(COLUMN)).as("the source field itself is left untouched").isEqualTo("2024-01-15");
    assertThat(row.get("year")).as("the match is written to a DIFFERENT column").isEqualTo("2024");
  }
}
