package io.lumnik.hub.connector.mapping.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link DynamicTableWriter#SYSTEM_COLUMNS} against the DDL that creates those columns, and
 * pins the collision refusal ({@link DynamicTableWriter#refuseSystemColumnCollisions}) that keeps
 * a source column from silently impersonating one of them.
 */
class DynamicTableSystemColumnsTest {

  @Test
  void systemColumns_matchTheDdlThatCreatesThem() {
    Map<String, String> payload = Map.of("amount", "NUMERIC");
    String ddl = new DynamicTableWriter().createTableDdl(
        new MappingTarget.Table("connector", "t_x", true, MappingTarget.OnConflict.SKIP, List.of()),
        payload);

    // The DDL's column list is everything between the outer parens; each column definition is
    // "name TYPE...", comma-separated (payload columns come through quoteIdent, hence the
    // stripped double-quotes below). Whatever remains after removing the known payload
    // column(s) is the fixed prelude — it must equal SYSTEM_COLUMNS exactly, in both
    // directions: a name SYSTEM_COLUMNS claims but the DDL doesn't create, and a fixed column
    // the DDL creates but SYSTEM_COLUMNS doesn't know about, are both drift.
    Set<String> fixedColumns = new HashSet<>(columnsOf(ddl));
    fixedColumns.removeAll(payload.keySet());

    assertThat(fixedColumns).as("the DDL's fixed prelude columns must equal SYSTEM_COLUMNS exactly")
        .isEqualTo(DynamicTableWriter.SYSTEM_COLUMNS);
  }

  /**
   * The names a {@code CREATE TABLE} body declares, in the order it declares them.
   *
   * <p>Package-visible because {@code SourceFieldOrderTest} asks the same DDL a different
   * question — which columns, in which ORDER — and had copied the parse character for character.
   *
   * <p><b>Splits on TOP-LEVEL commas only.</b> The obvious {@code split(",\\s*")} was correct for
   * exactly as long as no column type contained a comma; {@code NUMERIC(18,4)} broke it into
   * {@code "amount" NUMERIC(18} and {@code 4)}, inventing a column called {@code 4)}. It had been
   * true since the day it was written and became false when whole-number typing landed
   * {@code NUMERIC(18,4)} in reach of these tests (caught in review). Quotes are tracked for the
   * same reason: the names come through {@code quoteIdent}, and a source field name is whatever
   * the source called it — the kafka bench alone ships {@code Partner Name} and {@code MONTANT_€}.
   */
  static List<String> columnsOf(String ddl) {
    String body = ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')'));
    List<String> names = new ArrayList<>();
    StringBuilder def = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (!quoted && c == '(') {
        depth++;
      } else if (!quoted && c == ')') {
        depth--;
      } else if (!quoted && depth == 0 && c == ',') {
        names.add(nameOf(def.toString()));
        def.setLength(0);
        continue;
      }
      def.append(c);
    }
    if (!def.isEmpty()) names.add(nameOf(def.toString()));
    return names;
  }

  /**
   * The column name out of one definition: the quoted identifier, unescaped, or the first bare
   * word.
   *
   * <p>{@code quoteIdent} DOUBLES an embedded quote, so a source field called {@code a"b} ships as
   * {@code "a""b"}. Reading to the first inner quote stopped at {@code a} and, for a name that
   * survived that far, handed back the escaped spelling rather than the identifier — the test
   * would then compare a name no source ever used (caught in review). The split above already
   * handled {@code ""} by accident (two toggles cancel); this did not.
   */
  private static String nameOf(String definition) {
    String d = definition.trim();
    if (!d.startsWith("\"")) return d.split("\\s+")[0];
    StringBuilder name = new StringBuilder();
    for (int i = 1; i < d.length(); i++) {
      char c = d.charAt(i);
      if (c == '"') {
        if (i + 1 < d.length() && d.charAt(i + 1) == '"') {   // an escaped quote, one character
          name.append('"');
          i++;
          continue;
        }
        break;                                                 // the closing quote
      }
      name.append(c);
    }
    return name.toString();
  }

  @Test
  void payloadColumnCollidingWithASystemColumn_isRefused() {
    assertThatThrownBy(() -> DynamicTableWriter.refuseSystemColumnCollisions(
            List.of(Map.of("id", 1, "label", "x"))))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("id")
        .hasMessageContaining("rename");
  }

  /**
   * The remedy must be true for every connector that reaches this writer, and only those three do
   * ({@code MultiTargetWriter} routes a {@code MappingTarget.Table} here). csv reads row hooks from
   * its manifest ({@code CsvFileConnectorType.buildSpec}) and jdbc has {@code rename}, but
   * {@code KafkaConnectorType.dumpSpec} hardcodes empty hook tiers — naming a map-columns hook
   * without qualifying it would send a kafka installer after a gesture that does not exist.
   */
  @Test
  void theRemedyNamesEveryConnectorThatReachesThisWriter() {
    assertThatThrownBy(() -> DynamicTableWriter.refuseSystemColumnCollisions(
            List.of(Map.of("id", 1))))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("jdbc")
        .hasMessageContaining("csv")
        .hasMessageContaining("kafka");
  }

  @Test
  void payloadWithoutCollision_passes() {
    assertThatCode(() -> DynamicTableWriter.refuseSystemColumnCollisions(
            List.of(Map.of("invoice_id", 1, "label", "x")))).doesNotThrowAnyException();
  }
}
