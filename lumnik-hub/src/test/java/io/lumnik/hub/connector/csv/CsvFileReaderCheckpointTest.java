package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkpoint-capable window read (lot-5b): same skip/maxGood/malformed contract as
 * {@link CsvFileReader#readWindow}, plus a resumable position for the window's end. The
 * equivalence property: a chain of checkpoint-resumed windows must produce exactly what one
 * straight {@link CsvFileReader#readAll} produces — rows, order, malformed set — including
 * over quoted embedded newlines, CRLF line endings and multi-byte characters (the content
 * that kills any line- or byte-counting shortcut).
 */
class CsvFileReaderCheckpointTest {

  private static CsvFileReader.Config cfg(Charset cs, boolean hasHeader, List<String> columns) {
    return new CsvFileReader.Config(cs, ',', hasHeader, true,
        List.of("", "NULL", "null", "#N/A"), columns);
  }

  /** Chain checkpointed windows of {@code maxGood} over {@code bytes}, resuming via slice + resume(). */
  private static Chained chain(byte[] bytes, Charset cs, CsvFileReader.Config first, int maxGood,
                               CsvFileReader reader) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<MalformedRecord> bad = new ArrayList<>();
    long absoluteAnchor = 0;
    CheckpointingReader rdr = new CheckpointingReader(new ByteArrayInputStream(bytes), cs);
    CsvFileReader.CheckpointedWindow w =
        reader.readWindowCheckpointed(rdr, "mem.csv", first, 0, maxGood, 0L, rows::add, bad::add);
    List<String> headers = w.headers();
    int windows = 1;
    while (!w.fileExhausted()) {
      assertThat(w.nextCheckpoint()).as("non-exhausted window must name its resume point").isNotNull();
      absoluteAnchor += w.nextCheckpoint().anchorByte();
      var in = new ByteArrayInputStream(bytes, (int) absoluteAnchor, bytes.length - (int) absoluteAnchor);
      CheckpointingReader resumed = CheckpointingReader.resume(in, cs, w.nextCheckpoint().charDelta());
      w = reader.readWindowCheckpointed(resumed, "mem.csv", cfg(cs, false, headers),
          0, maxGood, w.nextLineBase(), rows::add, bad::add);
      windows++;
    }
    return new Chained(rows, bad, headers, windows);
  }

  private record Chained(List<Map<String, Object>> rows, List<MalformedRecord> bad,
                         List<String> headers, int windows) {}

  @Test
  void first_window_captures_stripped_headers_and_a_checkpoint() throws Exception {
    byte[] bytes = "\uFEFFid,name\n1,a\n2,b\n3,c\n".getBytes(StandardCharsets.UTF_8);
    List<Map<String, Object>> rows = new ArrayList<>();
    var reader = new CsvFileReader();
    var w = reader.readWindowCheckpointed(
        new CheckpointingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
        "mem.csv", cfg(StandardCharsets.UTF_8, true, null), 0, 2, 0L, rows::add, m -> {});
    assertThat(w.headers()).as("BOM stripped from the captured header").containsExactly("id", "name");
    assertThat(w.consumed()).isEqualTo(2);
    assertThat(w.fileExhausted()).isFalse();
    assertThat(w.nextCheckpoint()).isNotNull();
    assertThat(rows).extracting(r -> r.get("id")).containsExactly("1", "2");
  }

  @Test
  void chained_resume_equals_straight_readAll_over_hostile_content(@TempDir Path tmp) throws Exception {
    // Quoted embedded newlines (a record is NOT a line), CRLF endings, multi-byte chars,
    // and two malformed rows — one early, one late.
    StringBuilder sb = new StringBuilder("id,désc\r\n");
    for (int i = 1; i <= 23; i++) {
      if (i == 5 || i == 19) {
        sb.append("ONLY_ONE_COLUMN\r\n");
        continue;
      }
      sb.append(i).append(",\"héllo — 中文 line1\nline2 of ").append(i).append("\"\r\n");
    }
    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

    // Reference: one straight readAll over the same bytes.
    Path file = tmp.resolve("ref.csv");
    Files.write(file, bytes);
    List<Map<String, Object>> refRows = new ArrayList<>();
    List<MalformedRecord> refBad = new ArrayList<>();
    new CsvFileReader().readAll(file, cfg(StandardCharsets.UTF_8, true, null),
        refRows::add, refBad::add);

    // Small batch size is exercised inside CheckpointingReader's own tests; here windows of 3
    // force ~8 resume hops across the malformed rows and the quoted newlines.
    Chained c = chain(bytes, StandardCharsets.UTF_8, cfg(StandardCharsets.UTF_8, true, null), 3,
        new CsvFileReader());

    assertThat(c.windows()).isGreaterThanOrEqualTo(7);
    assertThat(c.headers()).containsExactly("id", "désc");
    assertThat(c.rows()).as("rows: identical content, identical order").isEqualTo(refRows);
    assertThat(c.bad()).hasSize(refBad.size()).hasSize(2);
    assertThat(c.bad()).extracting(MalformedRecord::rawValues)
        .isEqualTo(refBad.stream().map(MalformedRecord::rawValues).toList());
    // DLQ line numbers must stay FILE-absolute in resumed windows (the parser restarts its
    // line counter at the anchor; the carried lineBase adds it back) — quoted embedded
    // newlines included, since physical lines ≠ records here.
    assertThat(c.bad()).extracting(MalformedRecord::line)
        .isEqualTo(refBad.stream().map(MalformedRecord::line).toList());
  }

  @Test
  void utf16le_chain_equals_readAll(@TempDir Path tmp) throws Exception {
    Charset cs = StandardCharsets.UTF_16LE;
    StringBuilder sb = new StringBuilder("id,name\n");
    for (int i = 1; i <= 10; i++) sb.append(i).append(",naïve-").append(i).append('\n');
    byte[] bytes = sb.toString().getBytes(cs);

    Path file = tmp.resolve("wide.csv");
    Files.write(file, bytes);
    List<Map<String, Object>> refRows = new ArrayList<>();
    new CsvFileReader().readAll(file, cfg(cs, true, null), refRows::add, m -> {});

    Chained c = chain(bytes, cs, cfg(cs, true, null), 4, new CsvFileReader());
    assertThat(c.rows()).isEqualTo(refRows);
  }

  @Test
  void headerless_files_echo_the_configured_columns(@TempDir Path tmp) throws Exception {
    byte[] bytes = "1,a\n2,b\n3,c\n4,d\n".getBytes(StandardCharsets.UTF_8);
    Chained c = chain(bytes, StandardCharsets.UTF_8,
        cfg(StandardCharsets.UTF_8, false, List.of("num", "val")), 2, new CsvFileReader());
    assertThat(c.headers()).containsExactly("num", "val");
    assertThat(c.rows()).extracting(r -> r.get("num")).containsExactly("1", "2", "3", "4");
  }

  @Test
  void desync_just_past_the_boundary_yields_more_but_no_checkpoint() throws Exception {
    // Two good rows fill the window; the peeked third record has an unterminated quote.
    byte[] bytes = "a,b\n1,1\n2,2\n\"broken,3\n4,4\n".getBytes(StandardCharsets.UTF_8);
    List<Map<String, Object>> rows = new ArrayList<>();
    var w = new CsvFileReader().readWindowCheckpointed(
        new CheckpointingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
        "mem.csv", cfg(StandardCharsets.UTF_8, true, null), 0, 2, 0L, rows::add, m -> {});
    assertThat(rows).hasSize(2);
    assertThat(w.fileExhausted()).as("the desync is the NEXT window's problem, as on the plain path").isFalse();
    assertThat(w.nextCheckpoint()).as("no checkpoint → the caller writes a legacy cursor").isNull();
  }

  @Test
  void exact_boundary_end_reports_exhausted_with_no_checkpoint() throws Exception {
    byte[] bytes = "a,b\n1,1\n2,2\n".getBytes(StandardCharsets.UTF_8);
    List<Map<String, Object>> rows = new ArrayList<>();
    var w = new CsvFileReader().readWindowCheckpointed(
        new CheckpointingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
        "mem.csv", cfg(StandardCharsets.UTF_8, true, null), 0, 2, 0L, rows::add, m -> {});
    assertThat(rows).hasSize(2);
    assertThat(w.fileExhausted()).isTrue();
    assertThat(w.nextCheckpoint()).isNull();
  }
}
