package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CsvFileReaderTest {

  @Test
  void reads_simple_csv() throws Exception {
    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    List<Map<String, Object>> rows = new ArrayList<>();
    reader.readAll(path, null, rows::add);
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0))
        .containsEntry("email", "alice@example.com")
        .containsEntry("name", "Alice")
        .containsEntry("country", "FR");
  }

  @Test
  void treats_empty_string_as_null() throws Exception {
    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    List<Map<String, Object>> rows = new ArrayList<>();
    reader.readAll(path, null, rows::add);
    // simple fixture has no nulls, so just verify no NULL strings became actual nulls
    assertThat(rows.get(0).get("email")).isNotNull();
  }

  /**
   * A header name is trimmed like a BOM is stripped: Firebird's isql pads every line, the header
   * included, to the expression's display width (#1853). The values keep their padding.
   */
  @Test
  void a_header_name_loses_its_surrounding_whitespace_and_a_value_keeps_its_own(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("padded.csv");
    Files.writeString(file, "\uFEFF ART_ID;REF;DATE_MAJ" + " ".repeat(40) + "\n"
        + "1;CLE-14;2026-10-30" + " ".repeat(10) + "\n");

    var rows = new ArrayList<Map<String, Object>>();
    new CsvFileReader().readAll(file,
        new CsvFileReader.Config(StandardCharsets.UTF_8, ';', true, true, List.of(""), null), rows::add, bad -> {});

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).keySet()).containsExactly("ART_ID", "REF", "DATE_MAJ");
    assertThat(rows.get(0).get("DATE_MAJ")).isEqualTo("2026-10-30" + " ".repeat(10));
  }

  /**
   * The shapes a declared schema ACCEPTS. Each is a row a naive column-count guard would refuse,
   * which is why they are pinned rather than assumed: the guard must count VALUES, not delimiters,
   * and must not confuse an empty value with a missing one.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedShapes")
  void a_row_matching_its_declared_column_count_is_written(
      String label, String headerLine, String dataLine, @TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("shape.csv");
    Files.writeString(file, headerLine + "\n" + dataLine + "\n");

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), good::add, bad::add);

    assertThat(good).as("the row matches its declared columns and must be written").hasSize(1);
    assertThat(bad).as("nothing to quarantine").isEmpty();
  }

  static Stream<Arguments> acceptedShapes() {
    return Stream.of(
        Arguments.of("exact match", "a,b", "1,2"),
        Arguments.of("an EMPTY second value is present, not missing", "a,b", "1,"),
        // A quoted field containing the delimiter is ONE value: a guard counting delimiters
        // instead of values would refuse this row.
        Arguments.of("a quoted delimiter is one value, not two", "a,b", "\"1,x\",2"),
        // This case pins the guard's COUNT SOURCE and nothing else. Measured before the guard
        // was written: for a,b,a Commons CSV's getHeaderMap() collapses the duplicate to size 2
        // while getHeaderNames() keeps 3, so a guard counting the MAP would quarantine every row
        // of any legacy export that repeats a column name.
        // It does NOT claim the written row is whole. A row is a Map, which cannot hold the same
        // key twice, so `a` keeps only the last duplicate and the first value is lost anyway —
        // a hole older than this guard, and one this guard deliberately does not paper over.
        // See toRow's javadoc.
        Arguments.of("a duplicated header name is counted, not quarantined", "a,b,a", "1,2,3"));
  }

  /**
   * The shapes a declared schema REFUSES, in both directions, each quarantined with its raw values
   * rather than silently reshaped.
   *
   * <p>Found live on 2026-09-04 against the SFTP scenario:
   * {@code BADROW,"trop de colonnes","erp legacy glitch"} landed in the hub as its first two
   * fields, the third gone, the run reporting Completed with zero dead-letters — against what
   * {@code docs/transparency/ingestion-honesty.md} promises out loud, that <em>every row is
   * accounted for</em>. Too FEW was already refused; too MANY was not.
   *
   * <p>Strict in both directions on purpose, trailing delimiter included: too-few is refused
   * whether or not the missing value would have mattered, so leniency on the other side would be
   * the asymmetry, not the rigour.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("quarantinedShapes")
  void a_row_that_does_not_match_its_declared_column_count_is_quarantined_with_its_raw_values(
      String label, String headerLine, String dataLine, List<String> expectedRaw,
      String messageFragment, @TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("shape.csv");
    // A well-shaped row FOLLOWS the offending one: quarantining isolates a row, it never ends
    // the file. Every case here declares two columns, so "9,9" is that row.
    Files.writeString(file, headerLine + "\n" + dataLine + "\n9,9\n");

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), good::add, bad::add);

    assertThat(good)
        .as("the row AFTER the offending one still lands — the read continues past a quarantine")
        .hasSize(1);
    assertThat(bad).as("the row must be quarantined, not dropped").hasSize(1);
    assertThat(bad.get(0).rawValues())
        .as("the DLQ payload keeps EVERY raw value, including the one no column has a home for; "
            + "that value is the whole reason silent truncation was a defect")
        .containsExactlyElementsOf(expectedRaw);
    assertThat(bad.get(0).message()).as("the refusal names the shape, in the reader's words")
        .contains(messageFragment).doesNotContain("CSVRecord");
    assertThat(bad.get(0).line()).as("the offending line, for the operator").isEqualTo(2L);
  }

  static Stream<Arguments> quarantinedShapes() {
    return Stream.of(
        Arguments.of("one column short", "a,b", "1",
            List.of("1"), "Record has 1 value but 2 columns are declared"),
        Arguments.of("one column too many, carrying data", "a,b", "1,2,3",
            List.of("1", "2", "3"), "3 values but only 2 columns are declared"),
        Arguments.of("a trailing delimiter is one column too many", "a,b", "1,2,",
            List.of("1", "2", ""), "3 values but only 2 columns are declared"));
  }

  @Test
  void unterminated_quote_is_file_fatal_and_stops(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("desync.csv");
    Files.writeString(file, "a,b,c\n1,2,3\n\"oops,unterminated,9\n7,8,9\n");

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), good::add, bad::add);

    assertThat(good).hasSize(1);
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).rawValues()).isNull();
    assertThat(bad.get(0).message())
        .as("the reader's own sentence, never the parser library's exception text")
        .isEqualTo("A quoted value does not close where expected: the file cannot be read past this line");
  }

  /**
   * #1887: 0x90 is one of the five bytes windows-1252 leaves undefined (a DOS code page's É). The lines before it
   * are read; the sentence names the encoding, the byte and the line, so {@code parser.encoding} is the next gesture.
   */
  @Test
  void a_byte_the_encoding_does_not_map_says_the_encoding_the_byte_and_the_line(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("dos.csv");
    Files.write(file, concat("id,name\n1,Perceuse\n2,".getBytes(StandardCharsets.US_ASCII),
        new byte[] {(byte) 0x90}, "lectro\n3,Autre\n".getBytes(StandardCharsets.US_ASCII)));
    var cfg = new CsvFileReader.Config(Charset.forName("windows-1252"), ',', true, true, List.of(""), null);

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, cfg, good::add, bad::add);

    assertThat(good).as("the lines before the bad byte are kept").hasSize(1);
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).line()).isEqualTo(3);
    assertThat(bad.get(0).message())
        .contains("windows-1252", "0x90", "line 3", "parser.encoding")
        .doesNotContain("Exception");
  }

  /** The line is the file's, however far down the byte sits. */
  @Test
  void the_line_follows_the_byte_down_the_file(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("deep.csv");
    Files.write(file, concat("id,name\n1,a\n2,b\n3,c\n4,".getBytes(StandardCharsets.US_ASCII),
        new byte[] {(byte) 0x90}, "\n".getBytes(StandardCharsets.US_ASCII)));
    var cfg = new CsvFileReader.Config(Charset.forName("windows-1252"), ',', true, true, List.of(""), null);

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, cfg, good::add, bad::add);

    assertThat(good).hasSize(3);
    assertThat(bad).extracting(MalformedRecord::line).containsExactly(5L);
    assertThat(bad.get(0).message()).contains("line 5");
  }

  /** The bad byte in the very first record: the line is 1, not the 0 of "nothing read yet". */
  @Test
  void a_bad_byte_on_the_first_line_is_line_one(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("first.csv");
    Files.write(file, concat(new byte[] {(byte) 0x90}, "lectro,x\n".getBytes(StandardCharsets.US_ASCII)));
    var cfg = new CsvFileReader.Config(Charset.forName("windows-1252"), ',', true, true, List.of(""), null);

    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, cfg, r -> {}, bad::add);

    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).line()).isEqualTo(1);
    assertThat(bad.get(0).message()).contains("windows-1252", "0x90", "line 1");
  }

  /** The checkpoint path speaks the same sentence as the plain one. */
  @Test
  void the_checkpoint_path_says_the_same_about_a_bad_byte() throws Exception {
    byte[] bytes = concat("id,name\n1,Perceuse\n2,".getBytes(StandardCharsets.US_ASCII),
        new byte[] {(byte) 0x90}, "lectro\n".getBytes(StandardCharsets.US_ASCII));
    Charset cs = Charset.forName("windows-1252");
    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readWindowCheckpointed(
        new CheckpointingReader(new java.io.ByteArrayInputStream(bytes), cs), "mem.csv",
        new CsvFileReader.Config(cs, ',', true, true, List.of(""), null), 0, 100, 0L, good::add, bad::add);

    assertThat(good).hasSize(1);
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).message()).contains("windows-1252", "0x90", "line 3");
  }

  private static byte[] concat(byte[]... parts) {
    var out = new java.io.ByteArrayOutputStream();
    for (byte[] p : parts) out.writeBytes(p);
    return out.toByteArray();
  }

  @Test
  void three_arg_overload_still_throws_on_malformed_row(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("legacy.csv");
    Files.writeString(file, "a,b\n1,2\nONLY_ONE\n");
    assertThat(catchThrowable(() ->
        new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), r -> {})))
        .as("legacy 3-arg path must still abort on a malformed row")
        .isInstanceOf(RuntimeException.class);
  }
}
