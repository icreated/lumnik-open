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
 * #1877: DisplayTag's CsvView and MySQL's {@code INTO OUTFILE} escape a quote inside a quoted value with a backslash,
 * not by doubling it. {@code parser.escape} says so; without it the first such line desyncs the tokenizer and the rest
 * of the file is never read.
 */
class CsvEscapeTest {

  private static final String EXPORT = String.join("\n",
      "ref,date,customer,part,note,status,amount,qty",
      "RMA-1,28/08/2026,Dubreuil,V-40,\"Client: \\\"pièce jamais reçue\\\", relancé\",En expertise,\"179,80\",69",
      "RMA-2,29/08/2026,Martin,V-41,plain,Clos,\"1 234,50\",3",
      "RMA-3,30/08/2026,Durand,V-42,\"a \\\\ backslash\",Clos,10,1") + "\n";

  private static List<Map<String, Object>> read(Path file, Map<String, Object> parser, List<MalformedRecord> bad)
      throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    var cfg = CsvFileConnectorType.effectiveConfig(file, CsvFileConnectorType.buildParserOverrides(parser));
    new CsvFileReader().readAll(file, cfg, rows::add, bad::add);
    return rows;
  }

  @Test
  void a_backslash_escaped_quote_keeps_its_quote_and_the_file_is_read_to_the_end(@TempDir Path tmp) throws Exception {
    Path file = Files.writeString(tmp.resolve("rma.csv"), EXPORT, StandardCharsets.UTF_8);
    List<MalformedRecord> bad = new ArrayList<>();

    List<Map<String, Object>> rows = read(file, Map.of("escape", "\\"), bad);

    assertThat(bad).isEmpty();
    assertThat(rows).extracting(r -> r.get("ref")).containsExactly("RMA-1", "RMA-2", "RMA-3");
    assertThat(rows.get(0).get("note")).isEqualTo("Client: \"pièce jamais reçue\", relancé");
    assertThat(rows.get(0).get("amount")).isEqualTo("179,80");
    assertThat(rows.get(2).get("note")).isEqualTo("a \\ backslash");
  }

  @Test
  void headerless_files_honour_it_too(@TempDir Path tmp) throws Exception {
    Path file = Files.writeString(tmp.resolve("rma.csv"),
        "RMA-1,\"say \\\"hi\\\", ok\"\nRMA-2,plain\n", StandardCharsets.UTF_8);
    List<MalformedRecord> bad = new ArrayList<>();

    List<Map<String, Object>> rows = read(file,
        Map.of("escape", "\\", "has_header", false, "columns", List.of("ref", "note")), bad);

    assertThat(bad).isEmpty();
    assertThat(rows).extracting(r -> r.get("note")).containsExactly("say \"hi\", ok", "plain");
  }

  @Test
  void without_it_the_file_still_stops_at_the_escaped_quote_line(@TempDir Path tmp) throws Exception {
    Path file = Files.writeString(tmp.resolve("rma.csv"), EXPORT, StandardCharsets.UTF_8);
    List<MalformedRecord> bad = new ArrayList<>();

    List<Map<String, Object>> rows = read(file, Map.of(), bad);

    assertThat(rows).hasSizeLessThan(3);
    assertThat(bad).isNotEmpty();
  }

  @Test
  void the_key_reaches_the_config_and_a_blank_one_is_no_escape() {
    assertThat(CsvFileConnectorType.buildParserOverrides(Map.of("escape", "\\")).escape()).isEqualTo('\\');
    assertThat(CsvFileConnectorType.buildParserOverrides(Map.of("escape", "")).escape()).isNull();
    assertThat(CsvFileConnectorType.buildParserOverrides(Map.of()).escape()).isNull();
  }
}
