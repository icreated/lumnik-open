package io.lumnik.hub.connector.mapping.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    String columnList = ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')'));
    Set<String> fixedColumns = Arrays.stream(columnList.split(",\\s*"))
        .map(def -> def.trim().split("\\s+")[0].replaceAll("^\"|\"$", ""))
        .collect(Collectors.toCollection(HashSet::new));
    fixedColumns.removeAll(payload.keySet());

    assertThat(fixedColumns).as("the DDL's fixed prelude columns must equal SYSTEM_COLUMNS exactly")
        .isEqualTo(DynamicTableWriter.SYSTEM_COLUMNS);
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
