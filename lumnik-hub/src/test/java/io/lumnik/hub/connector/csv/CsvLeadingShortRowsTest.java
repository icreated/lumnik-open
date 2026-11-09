package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1863: an instrument export opens with a {@code key;value} block before its table. With
 * {@code short_rows: skip-leading} the lines that are too short BEFORE the first full-width row are skipped on
 * purpose — counted, never dead-lettered — and the table, once begun, is held to its width again.
 */
class CsvLeadingShortRowsTest {

  private static final List<String> COLUMNS = List.of("id", "x", "y");

  /** Headerless, ';', three columns declared; {@code skipLeading} is the option under test. */
  private static CsvFileReader.Config cfg(boolean skipLeading) {
    return new CsvFileReader.Config(StandardCharsets.UTF_8, ';', /*hasHeader*/ false, /*bomStrip*/ true,
        List.of(""), COLUMNS, /*skipLeadingShortRows*/ skipLeading);
  }

  /** A three-line block, then the table; row 3 is truncated AFTER the table began. */
  private static final String FILE = """
      BATCH;B-42
      INSTRUMENT;QTOF
      METHOD;QC
      1;a;b
      2;a;b
      3;a
      4;a;b
      """;

  private static List<MalformedRecord> declared(List<MalformedRecord> bad) {
    return bad.stream().filter(MalformedRecord::declaredSkip).toList();
  }

  private static List<MalformedRecord> deadLettered(List<MalformedRecord> bad) {
    return bad.stream().filter(m -> !m.declaredSkip()).toList();
  }

  @Test
  void the_short_lines_before_the_table_are_skipped_and_a_short_line_after_it_is_not(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("export.csv");
    Files.writeString(file, FILE);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();

    new CsvFileReader().readAll(file, cfg(true), rows::add, bad::add);

    assertThat(rows).extracting(r -> r.get("id")).containsExactly("1", "2", "4");
    assertThat(declared(bad)).extracting(MalformedRecord::line).containsExactly(1L, 2L, 3L);
    assertThat(deadLettered(bad)).as("the truncated data line is an anomaly, never swallowed")
        .extracting(MalformedRecord::line).containsExactly(6L);
    assertThat(deadLettered(bad).get(0).message()).contains("2 values but 3 columns are declared");
  }

  @Test
  void without_the_option_every_short_line_is_dead_lettered_as_before(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("export.csv");
    Files.writeString(file, FILE);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();

    new CsvFileReader().readAll(file, cfg(false), rows::add, bad::add);

    assertThat(rows).hasSize(3);
    assertThat(declared(bad)).isEmpty();
    assertThat(deadLettered(bad)).hasSize(4);
  }

  /** A line WIDER than declared is a full-width-or-more row: the table has begun, and the row is refused as ever. */
  @Test
  void a_too_wide_line_begins_the_table_and_is_dead_lettered(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("wide.csv");
    Files.writeString(file, "KEY;VALUE\n1;a;b;SURPLUS\nSHORT;a\n2;a;b\n");
    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();

    new CsvFileReader().readAll(file, cfg(true), rows::add, bad::add);

    assertThat(rows).extracting(r -> r.get("id")).containsExactly("2");
    assertThat(declared(bad)).extracting(MalformedRecord::line).containsExactly(1L);
    assertThat(deadLettered(bad)).extracting(MalformedRecord::line).containsExactly(2L, 3L);
  }

  /** A resume by skipping events from the top (the legacy cursor): where the table began is re-derived. */
  @Test
  void a_resume_inside_the_table_never_skips_again_and_inside_the_block_keeps_skipping(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("export.csv");
    Files.writeString(file, FILE);

    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();
    new CsvFileReader().readWindow(file, cfg(true), /*skip*/ 4, 100, rows::add, bad::add);
    assertThat(rows).extracting(r -> r.get("id")).containsExactly("2", "4");
    assertThat(declared(bad)).isEmpty();
    assertThat(deadLettered(bad)).extracting(MalformedRecord::line).containsExactly(6L);

    rows.clear();
    bad.clear();
    new CsvFileReader().readWindow(file, cfg(true), /*skip*/ 1, 100, rows::add, bad::add);
    assertThat(rows).hasSize(3);
    assertThat(declared(bad)).extracting(MalformedRecord::line).containsExactly(2L, 3L);
  }

  /** The checkpoint path: windows of one good row, each resumed headerless from the previous one's anchor. */
  @Test
  void a_checkpoint_resume_remembers_the_table_has_begun() throws Exception {
    byte[] bytes = FILE.getBytes(StandardCharsets.UTF_8);
    var reader = new CsvFileReader();
    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();

    long anchor = 0;
    var w = reader.readWindowCheckpointed(
        new CheckpointingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
        "mem.csv", cfg(true), 0, 1, 0L, rows::add, bad::add);
    while (!w.fileExhausted()) {
      anchor += w.nextCheckpoint().anchorByte();
      var in = new ByteArrayInputStream(bytes, (int) anchor, bytes.length - (int) anchor);
      // What production does: the resumed window is headerless, on the cursor's columns, with no option to carry.
      var resumedCfg = new CsvFileReader.Config(StandardCharsets.UTF_8, ';', false, true, List.of(""), COLUMNS);
      w = reader.readWindowCheckpointed(CheckpointingReader.resume(in, StandardCharsets.UTF_8, w.nextCheckpoint().charDelta()),
          "mem.csv", resumedCfg, 0, 1, w.nextLineBase(), rows::add, bad::add);
    }

    assertThat(rows).extracting(r -> r.get("id")).containsExactly("1", "2", "4");
    assertThat(declared(bad)).hasSize(3);
    assertThat(deadLettered(bad)).extracting(MalformedRecord::line).containsExactly(6L);
  }
}
