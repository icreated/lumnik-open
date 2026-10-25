package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1415: {@code parser.lines: true} reads a line-oriented file (a log) one line = one row, one column, with no
 * field splitting and no quoting — a line that opens a quote it never closes no longer swallows the rest of the file.
 */
class CsvLinesTest {

  private static final String LOG = String.join("\n",
      "2025-10-20 08:12:45,123 INFO  [srv-erp-prd01] c.a.OrderService - Order CMD-1001 created",
      "\"Unhandled rejection: connection reset",
      "\tat com.acme.order.OrderService.submit(OrderService.java:212)",
      "",
      "2025-10-20 08:13:05,004 WARN  [srv-erp-prd02] c.a.StockService - Stock \"EAN 3017620422003\" below threshold",
      "2025-10-20 08:14:00,000 ERROR [srv-erp-prd02] c.a.Api - GET /orders?status=open;page=2, 500") + "\n";

  private static Path log(Path tmp) throws Exception {
    return Files.writeString(tmp.resolve("app.log"), LOG, StandardCharsets.UTF_8);
  }

  private static List<Map<String, Object>> read(Path file, CsvFileReader.Config cfg, List<MalformedRecord> malformed)
      throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    new CsvFileReader().readAll(file, cfg, rows::add, malformed::add);
    return rows;
  }

  @Test
  void every_line_is_one_row_as_written_whatever_quote_tab_comma_or_semicolon_it_holds(@TempDir Path tmp)
      throws Exception {
    Path file = log(tmp);
    var overrides = CsvFileConnectorType.buildParserOverrides(Map.of("lines", true));
    List<MalformedRecord> malformed = new ArrayList<>();

    List<Map<String, Object>> rows = read(file, CsvFileConnectorType.effectiveConfig(file, overrides), malformed);

    assertThat(malformed).isEmpty();
    assertThat(rows).extracting(r -> r.get("line")).containsExactly(
        "2025-10-20 08:12:45,123 INFO  [srv-erp-prd01] c.a.OrderService - Order CMD-1001 created",
        "\"Unhandled rejection: connection reset",
        "\tat com.acme.order.OrderService.submit(OrderService.java:212)",
        "2025-10-20 08:13:05,004 WARN  [srv-erp-prd02] c.a.StockService - Stock \"EAN 3017620422003\" below threshold",
        "2025-10-20 08:14:00,000 ERROR [srv-erp-prd02] c.a.Api - GET /orders?status=open;page=2, 500");
  }

  /**
   * Review of #1416: the unit separator is the reader's marker of line mode, but a line may hold one (ASCII's own
   * field separator, in a legacy export). The line still lands whole, as written.
   */
  @Test
  void a_line_holding_the_unit_separator_lands_whole(@TempDir Path tmp) throws Exception {
    Path file = Files.writeString(tmp.resolve("legacy.dat"), "legacy\u001Fpayload\u001F\u001Fend\nnext\n",
        StandardCharsets.UTF_8);
    var overrides = CsvFileConnectorType.buildParserOverrides(Map.of("lines", true));
    List<MalformedRecord> malformed = new ArrayList<>();

    List<Map<String, Object>> rows = read(file, CsvFileConnectorType.effectiveConfig(file, overrides), malformed);

    assertThat(malformed).isEmpty();
    assertThat(rows).extracting(r -> r.get("line")).containsExactly("legacy\u001Fpayload\u001F\u001Fend", "next");
  }

  @Test
  void columns_renames_the_one_column() {
    var overrides = CsvFileConnectorType.buildParserOverrides(Map.of("lines", true, "columns", List.of("message")));

    assertThat(overrides.columns()).containsExactly("message");
    assertThat(overrides.hasHeader()).isFalse();
  }

  @Test
  void without_lines_the_same_headerless_file_loses_the_lines_after_the_open_quote(@TempDir Path tmp)
      throws Exception {
    Path file = log(tmp);
    var cfg = new CsvFileReader.Config(StandardCharsets.UTF_8, '\u0001',
        /*hasHeader*/ false, /*bomStrip*/ true, List.of(""), List.of("line"));
    List<MalformedRecord> malformed = new ArrayList<>();

    List<Map<String, Object>> rows = read(file, cfg, malformed);

    // The behaviour #1415 gives a way out of, pinned: the quote opened on line 2 runs to the end of the file.
    assertThat(rows).hasSize(1);
    assertThat(malformed).singleElement().satisfies(m -> assertThat(m.rawValues()).isNull());
  }
}
