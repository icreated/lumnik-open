package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.SeekableSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot 5b, task 4 (B2): the remote CSV path must produce CHUNK-BOUNDED reads, ending the
 * one-file-one-chunk heap materialisation.
 *
 * <ul>
 *   <li><b>Seekable transport (S3 ranged GET / SFTP offset):</b> checkpointed windows via
 *       {@code openAt} — the file is parsed once across the run (the O(R) work-count proof,
 *       same pattern as {@link CsvCheckpointWorkCountTest}).</li>
 *   <li><b>Non-seekable transport:</b> the fallback STREAMS with bounded memory — skip-parse
 *       over the transport stream, still multiple bounded chunks, never the whole file in one
 *       heap-resident list. Non-seekable transports live on this path permanently, so it is
 *       load-bearing, not a corner.</li>
 *   <li><b>A remote file mutated mid-run</b> ({@code sameVersion} false between chunks) fails
 *       LOUDLY naming the file — never a silent restart of a partially-ingested file.</li>
 *   <li><b>Multi-file semantics unchanged:</b> one file at a time, earliest-lastModified first,
 *       archive deferred until the persisted cursor proves the commit (A8), chunks never span
 *       two files.</li>
 * </ul>
 */
class FileSourceCursorReaderChunkingTest {

  private static final Instant T1 = Instant.parse("2026-08-12T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-08-12T10:05:00Z");

  // -------------------------------------------------------------------------
  // Fakes: in-memory FileSource, with and without the SeekableSource capability
  // -------------------------------------------------------------------------

  static class FakeSource implements FileSource {
    final Map<String, byte[]> content = new LinkedHashMap<>();
    final List<FileDescriptor> files = new ArrayList<>();
    final List<String> marked = new ArrayList<>();
    int opens;
    int openAts;

    void put(String path, String text, Instant mtime) {
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      content.put(path, b);
      files.removeIf(f -> f.path().equals(path));
      files.add(new FileDescriptor(path, b.length, mtime, null));
    }

    void remove(String path) {
      content.remove(path);
      files.removeIf(f -> f.path().equals(path));
    }

    @Override public Stream<FileDescriptor> listSince(Instant cursor) {
      return files.stream().filter(f -> !f.lastModified().isBefore(cursor));
    }

    @Override public InputStream open(FileDescriptor file) {
      opens++;
      return new ByteArrayInputStream(content.get(file.path()));
    }

    @Override public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
      marked.add(file.path());
      files.removeIf(f -> f.path().equals(file.path()));
    }
  }

  /** Same fake, plus a real positioned read — the S3-ranged-GET / SFTP-offset stand-in. */
  static final class SeekableFakeSource extends FakeSource implements SeekableSource {
    @Override public InputStream openAt(FileDescriptor file, long byteOffset) {
      openAts++;
      if (byteOffset < 0) throw new IllegalArgumentException("negative offset " + byteOffset);
      byte[] c = content.get(file.path());
      int off = (int) Math.min(byteOffset, c.length);
      return new ByteArrayInputStream(c, off, c.length - off);
    }
  }

  // -------------------------------------------------------------------------
  // Harness
  // -------------------------------------------------------------------------

  private static String csv(int from, int to) {
    StringBuilder sb = new StringBuilder("id,name\n");
    for (int i = from; i <= to; i++) sb.append(i).append(",row-").append(i).append('\n');
    return sb.toString();
  }

  private static FileSourceRegistry registryOf(FakeSource source) {
    return new FileSourceRegistry() {
      @Override public FileSource build(Map<String, Object> transportCfg) { return source; }
    };
  }

  private static FileSourceCursorReader reader(FakeSource source, String afterProcess) {
    Map<String, Object> parser = Map.of("encoding", "UTF-8", "delimiter", ",", "has_header", true);
    Map<String, Object> config = Map.of(
        "transport", Map.of("kind", "sftp"),
        "after_process", afterProcess,
        "parser", parser);
    return new FileSourceCursorReader(config,
        CsvFileConnectorType.buildParserOverrides(parser), registryOf(source));
  }

  /** One full drain: fresh reader per read() (mirrors the engine), collecting per-read sizes. */
  private record Drained(List<Map<String, Object>> rows, List<Integer> chunkSizes,
                         List<MalformedRecord> malformed) {}

  private static Drained drain(FakeSource source, int chunkSize) {
    List<Map<String, Object>> all = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    List<MalformedRecord> malformed = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    while (true) {
      FileSourceCursorReader r = reader(source, "leave");
      ReadResult<Map<String, Object>> res = r.read(chunkSize, cursor, null);
      malformed.addAll(r.drainMalformed());
      if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        sizes.add(hm.records().size());
        cursor = hm.nextCursor();
      } else {
        var eos = (ReadResult.EndOfStream<Map<String, Object>>) res;
        all.addAll(eos.records());
        if (!eos.records().isEmpty()) sizes.add(eos.records().size());
        break;
      }
    }
    return new Drained(all, sizes, malformed);
  }

  private static void assertConservedInOrder(List<Map<String, Object>> rows, int n) {
    assertThat(rows).hasSize(n);
    for (int i = 0; i < n; i++) {
      assertThat(rows.get(i).get("id")).isEqualTo(String.valueOf(i + 1));
    }
  }

  // -------------------------------------------------------------------------
  // 1. The heap-bounding red: seekable remote path must chunk, not materialise
  // -------------------------------------------------------------------------

  @Test
  void seekable_remote_file_is_read_in_chunk_bounded_windows() {
    var source = new SeekableFakeSource();
    source.put("/in/big.csv", csv(1, 1000), T1);

    Drained d = drain(source, 100);

    // The heap bound: no read() may hand the engine more than chunkSize rows.
    assertThat(d.chunkSizes())
        .as("each read() must return <= chunkSize rows — one-file-one-chunk is the bug under test")
        .allSatisfy(s -> assertThat(s).isLessThanOrEqualTo(100));
    assertThat(d.chunkSizes().size()).isGreaterThanOrEqualTo(10);
    assertConservedInOrder(d.rows(), 1000);
    assertThat(d.malformed()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // 2. The non-seekable fallback: streamed skip-parse, still bounded
  // -------------------------------------------------------------------------

  @Test
  void non_seekable_fallback_still_chunks_with_bounded_memory() {
    var source = new FakeSource();   // no SeekableSource capability → permanent fallback
    source.put("/in/big.csv", csv(1, 1000), T1);

    Drained d = drain(source, 100);

    assertThat(d.chunkSizes())
        .allSatisfy(s -> assertThat(s).isLessThanOrEqualTo(100));
    assertThat(d.chunkSizes().size()).isGreaterThanOrEqualTo(10);
    assertConservedInOrder(d.rows(), 1000);
    assertThat(d.malformed()).isEmpty();
    // Fallback memory story: each window opened the transport stream afresh and skip-parsed —
    // never a ranged seek (the capability is absent), never a full-file row list.
    assertThat(source.openAts).isZero();
    assertThat(source.opens).isGreaterThanOrEqualTo(10);
  }

  // -------------------------------------------------------------------------
  // 3. A file mutated between chunks fails LOUDLY, naming the file
  // -------------------------------------------------------------------------

  @Test
  void file_changed_mid_run_fails_loudly_naming_the_file() {
    var source = new SeekableFakeSource();
    source.put("/in/mutant.csv", csv(1, 300), T1);

    ReadResult<Map<String, Object>> r1 = reader(source, "leave").read(100, ConnectorCursor.empty(), null);
    assertThat(r1).isInstanceOf(ReadResult.HasMore.class);
    ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

    // The file mutates mid-ingestion: different content, size and mtime.
    source.put("/in/mutant.csv", csv(1, 305), T2);

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, null))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/mutant.csv")
        .hasMessageContaining("changed mid-ingestion");
  }

  @Test
  void file_vanished_mid_run_fails_loudly_naming_the_file() {
    var source = new SeekableFakeSource();
    source.put("/in/gone.csv", csv(1, 300), T1);

    ReadResult<Map<String, Object>> r1 = reader(source, "leave").read(100, ConnectorCursor.empty(), null);
    ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

    source.remove("/in/gone.csv");

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, null))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/gone.csv");
  }

  // -------------------------------------------------------------------------
  // 4. The O(R) proof on the seekable remote path (CsvCheckpointWorkCountTest pattern)
  // -------------------------------------------------------------------------

  @Test
  void seekable_remote_run_tokenizes_every_record_at_most_twice() {
    int n = 1000;
    var source = new SeekableFakeSource();
    source.put("/in/big.csv", csv(1, n), T1);

    // ONE reader instance across the run, so its parse-work counter spans every window.
    FileSourceCursorReader r = reader(source, "leave");
    List<Map<String, Object>> all = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    int reads = 0;
    while (true) {
      ReadResult<Map<String, Object>> res = r.read(100, cursor, null);
      reads++;
      if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        cursor = hm.nextCursor();
      } else {
        all.addAll(((ReadResult.EndOfStream<Map<String, Object>>) res).records());
        break;
      }
    }

    assertConservedInOrder(all, n);
    assertThat(reads).isGreaterThanOrEqualTo(10);
    assertThat(r.rowsTokenized())
        .as("records tokenized across the run (N=%d, %d reads) — must be O(R), not O(R²/K)", n, reads)
        .isLessThanOrEqualTo(2L * n);
  }

  // -------------------------------------------------------------------------
  // 5. Multi-file semantics unchanged: file advance, archive cadence, no chunk spans files
  // -------------------------------------------------------------------------

  @Test
  void multi_file_advance_and_deferred_archive_are_unchanged() {
    var source = new SeekableFakeSource();
    source.put("/in/a.csv", csv(1, 250), T1);
    source.put("/in/b.csv", csv(1, 50), T2);

    ConnectorCursor cursor = ConnectorCursor.empty();

    // a.csv: three windows (100/100/50); never archived while its rows are still flowing.
    for (int expected : new int[]{100, 100, 50}) {
      var res = (ReadResult.HasMore<Map<String, Object>>) reader(source, "delete").read(100, cursor, null);
      assertThat(res.records()).hasSize(expected);
      assertThat(res.records()).allSatisfy(row -> assertThat(row).containsKey("id"));
      cursor = res.nextCursor();
      assertThat(source.marked)
          .as("a file must never be archived before its final chunk's cursor is persisted")
          .isEmpty();
    }

    // b.csv's first read: the persisted cursor now proves a.csv committed → archive it first.
    var res = (ReadResult.HasMore<Map<String, Object>>) reader(source, "delete").read(100, cursor, null);
    assertThat(source.marked).containsExactly("/in/a.csv");
    assertThat(res.records()).hasSize(50);
    // Chunks never span two files: this chunk is pure b.csv even though 50 < chunkSize.
    assertThat(res.records().get(0).get("name")).isEqualTo("row-1");
    cursor = res.nextCursor();

    // Final read: b.csv archived, nothing new → EndOfStream.
    var eos = reader(source, "delete").read(100, cursor, null);
    assertThat(source.marked).containsExactly("/in/a.csv", "/in/b.csv");
    assertThat(eos).isInstanceOf(ReadResult.EndOfStream.class);
  }

  // -------------------------------------------------------------------------
  // 6. DLQ fidelity across windows: remote path + file-absolute line numbers
  // -------------------------------------------------------------------------

  @Test
  void malformed_row_in_a_later_window_keeps_remote_path_and_absolute_line() {
    var source = new SeekableFakeSource();
    StringBuilder sb = new StringBuilder("id,name\n");
    for (int i = 1; i <= 150; i++) {
      if (i == 130) sb.append("130\n");   // short row: column-count mismatch → isolated
      else sb.append(i).append(",row-").append(i).append('\n');
    }
    source.put("/in/dirty.csv", sb.toString(), T1);

    Drained d = drain(source, 100);

    assertThat(d.rows()).hasSize(149);
    assertThat(d.malformed()).hasSize(1);
    MalformedRecord m = d.malformed().get(0);
    assertThat(m.file())
        .as("DLQ entries must name the REMOTE source, never an ephemeral temp download")
        .isEqualTo("/in/dirty.csv");
    assertThat(m.line())
        .as("DLQ line numbers must stay file-absolute in resumed windows")
        .isEqualTo(131L);   // header line 1 + 130 data rows
    assertThat(m.rawValues()).containsExactly("130");
  }
}
