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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three case hooks against values that are not text — and against the JVM's default locale.
 *
 * <p>Every other text hook in this package guards its type and skips what it cannot handle.
 * {@code lower}, {@code upper} and {@code titlecase} did not — the same omission, copied three
 * times — and called {@code v.toString()} on whatever the cell held. (The roll-call of which
 * siblings guard is deliberately not written out here: an earlier draft listed five, there were
 * six, and a hand-kept inventory of a set is the same failure one level up.)
 *
 * <p>Non-text values reach the cell tier routinely: {@code JdbcChunkReader} puts
 * {@code rs.getObject(i)} into the row raw, and {@code parse-date}/{@code parse-number} put a
 * {@code LocalDate} or a {@code BigDecimal} there themselves. Two costs, neither recoverable:
 * a {@code byte[]} became its Java identity string ({@code [B@4dc52559}) — the bytes are simply
 * gone — and a date or an amount became text, so {@code SchemaInferrer} typed the column
 * {@code TEXT} instead of {@code DATE} or {@code NUMERIC}, silently breaking range filters and
 * joins downstream. The manifest declared "case conversion".
 *
 * <p>The locale is the same defect one layer down: {@code toLowerCase()} with no argument follows
 * the JVM's default, and under a Turkish default {@code INVOICE} lowercases to {@code ınvoıce}
 * with a dotless i — an ASCII key silently corrupted by an operator's environment. The repo
 * already pins {@code Locale.ROOT} everywhere it folds case for comparison.
 */
@QuarkusTest
class CaseHooksTypeGuardTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private Object applyTo(String hook, Object value) throws Exception {
    JsonNode params = YAML.readTree("columns: [c]\n");
    var row = new HashMap<String, Object>();
    row.put("c", value);
    catalog.cell(hook).apply(row, params, new MappingState());
    return row.get("c");
  }

  @ParameterizedTest(name = "{0} leaves a {1} alone")
  @MethodSource("nonTextValues")
  void a_case_hook_does_not_touch_a_value_that_is_not_text(String hook, String label, Object value)
      throws Exception {
    assertThat(applyTo(hook, value))
        .as("%s asked for case conversion, not for this value to become a String — the column's "
            + "inferred SQL type depends on it, and a byte[] cannot be recovered from one", hook)
        .isSameAs(value);
  }

  static Stream<Arguments> nonTextValues() {
    // The cross product IS the point: the omission was copied three times, so the table has to
    // ask all three the same question.
    return Stream.of("lower", "upper", "titlecase").flatMap(hook -> Stream.of(
        Arguments.of(hook, "byte[] (a BYTEA/BLOB column)", new byte[]{1, 2, 3, 4}),
        Arguments.of(hook, "LocalDate (what parse-date just wrote)", LocalDate.of(2024, 1, 15)),
        Arguments.of(hook, "BigDecimal (what parse-number just wrote)", new BigDecimal("1234.50")),
        Arguments.of(hook, "Boolean", Boolean.TRUE)));
  }

  /**
   * The JVM default locale must not reach the data. Turkish is the classic break: its lowercase
   * of {@code I} is the dotless {@code ı}, so an ASCII column value changes meaning because of
   * where the hub happens to be running.
   */
  @Test
  void case_folding_does_not_follow_the_jvm_default_locale() throws Exception {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(applyTo("lower", "INVOICE"))
          .as("under a Turkish default locale, an unpinned toLowerCase gives 'ınvoıce'")
          .isEqualTo("invoice");
      assertThat(applyTo("upper", "invoice"))
          .as("and an unpinned toUpperCase gives 'İNVOİCE'")
          .isEqualTo("INVOICE");
      // titlecase folds the tail of each word with the same call, so it needs the same proof.
      // Its first letter goes through Character.toUpperCase, which is locale-independent already
      // — the tail is the half that was following the JVM default.
      assertThat(applyTo("titlecase", "INVOICE PAID"))
          .as("titlecase lowercases each word's tail, so an unpinned call returns that tail "
              + "with a dotless i")
          .isEqualTo("Invoice Paid");
    } finally {
      Locale.setDefault(original);
    }
  }
}
