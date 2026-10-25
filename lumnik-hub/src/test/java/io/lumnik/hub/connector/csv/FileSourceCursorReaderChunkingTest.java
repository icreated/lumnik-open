package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.SeekableSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
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

  private OwnedRunScope scope;

  @BeforeEach
  void freshRunScope() {
    scope = new OwnedRunScope("test");
  }

  @AfterEach
  void closeRunScope() {
    scope.close();
  }

  private ConnectorContext ctx() {
    return ReaderTestContexts.of(scope);
  }

  private static final Instant T1 = Instant.parse("2026-08-12T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-08-12T10:05:00Z");

  // -------------------------------------------------------------------------
  // Fakes: in-memory FileSource, with and without the SeekableSource capability
  // -------------------------------------------------------------------------

  static class FakeSource implements FileSource {
    final Map<String, byte[]> content = new LinkedHashMap<>();
    final List<FileDescriptor> files = new ArrayList<>();
    final List<String> marked = new ArrayList<>();
    boolean usable = true;
    int closes;
    int opens;
    int openAts;
    int lists;
    int stats;

    void put(String path, String text, Instant mtime) {
      put(path, text, mtime, null);
    }

    void put(String path, String text, Instant mtime, String etag) {
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      content.put(path, b);
      files.removeIf(f -> f.path().equals(path));
      files.add(new FileDescriptor(path, b.length, mtime, etag));
    }

    void remove(String path) {
      content.remove(path);
      files.removeIf(f -> f.path().equals(path));
    }

    @Override public boolean usable() { return usable; }

    @Override public void close() { closes++; }

    @Override public Stream<FileDescriptor> listSince(Instant cursor) {
      lists++;
      // The FileSource contract: ascending by lastModified (stable, so ties keep insertion order).
      return files.stream().filter(f -> !f.lastModified().isBefore(cursor))
          .sorted(Comparator.comparing(FileDescriptor::lastModified));
    }

    @Override public Optional<FileDescriptor> stat(String path) {
      stats++;
      return files.stream().filter(f -> f.path().equals(path)).findFirst();
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

  /** Hands out the given sources in order (the last one repeats) and counts how often it was asked to build. */
  private static final class CountingRegistry extends FileSourceRegistry {
    final FakeSource[] sources;
    int builds;

    CountingRegistry(FakeSource... sources) { this.sources = sources; }

    @Override public FileSource build(Map<String, Object> transportCfg) {
      return sources[Math.min(builds++, sources.length - 1)];
    }
  }

  private static FileSourceCursorReader reader(FakeSource source, String afterProcess) {
    return reader(registryOf(source), afterProcess);
  }

  private static FileSourceCursorReader reader(FileSourceRegistry registry, String afterProcess) {
    Map<String, Object> parser = Map.of("encoding", "UTF-8", "delimiter", ",", "has_header", true);
    Map<String, Object> config = Map.of(
        "transport", Map.of("kind", "sftp"),
        "after_process", afterProcess,
        "parser", parser);
    return new FileSourceCursorReader(config,
        CsvFileConnectorType.buildParserOverrides(parser), registry);
  }

  /** One full drain: fresh reader per read() (mirrors the engine), collecting per-read sizes. */
  private record Drained(List<Map<String, Object>> rows, List<Integer> chunkSizes,
                         List<MalformedRecord> malformed) {}

  private Drained drain(FakeSource source, int chunkSize) {
    return drain(new CountingRegistry(source), chunkSize, chunk -> { });
  }

  /** As above through a given registry; {@code afterChunk} gets the 1-based number of each chunk just read. */
  private Drained drain(FileSourceRegistry registry, int chunkSize, IntConsumer afterChunk) {
    List<Map<String, Object>> all = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    List<MalformedRecord> malformed = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    int chunk = 0;
    while (true) {
      FileSourceCursorReader r = reader(registry, "leave");
      ReadResult<Map<String, Object>> res = r.read(chunkSize, cursor, ctx());
      malformed.addAll(r.drainMalformed());
      if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        sizes.add(hm.records().size());
        cursor = hm.nextCursor();
        afterChunk.accept(++chunk);
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

    ReadResult<Map<String, Object>> r1 = reader(source, "leave").read(100, ConnectorCursor.empty(), ctx());
    assertThat(r1).isInstanceOf(ReadResult.HasMore.class);
    ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

    // The file mutates mid-ingestion: different content, size and mtime.
    source.put("/in/mutant.csv", csv(1, 305), T2);

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, ctx()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/mutant.csv")
        .hasMessageContaining("changed mid-ingestion");
  }

  @Test
  void file_vanished_mid_run_fails_loudly_naming_the_file() {
    var source = new SeekableFakeSource();
    source.put("/in/gone.csv", csv(1, 300), T1);

    ReadResult<Map<String, Object>> r1 = reader(source, "leave").read(100, ConnectorCursor.empty(), ctx());
    ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

    source.remove("/in/gone.csv");

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, ctx()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/gone.csv")
        .hasMessageContaining("disappeared mid-ingestion");
  }

  // The in-progress check is a stat since #898; each case below is a verdict the per-chunk
  // listing used to give, pinned so the stat keeps giving it.

  @Test
  void same_size_and_mtime_but_a_new_etag_is_a_changed_file() {
    var source = new SeekableFakeSource();
    source.put("/in/tagged.csv", csv(1, 300), T1, "\"v1\"");
    ConnectorCursor c1 = firstChunkCursor(source);

    source.put("/in/tagged.csv", csv(1, 300), T1, "\"v2\"");

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, ctx()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/tagged.csv")
        .hasMessageContaining("changed mid-ingestion");
  }

  @Test
  void a_file_moved_away_mid_run_has_disappeared_even_if_its_bytes_live_elsewhere() {
    var source = new SeekableFakeSource();
    source.put("/in/moved.csv", csv(1, 300), T1);
    ConnectorCursor c1 = firstChunkCursor(source);

    source.remove("/in/moved.csv");
    source.put("/in/archive/moved.csv", csv(1, 300), T1);

    assertThatThrownBy(() -> reader(source, "leave").read(100, c1, ctx()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/moved.csv")
        .hasMessageContaining("disappeared mid-ingestion");
  }

  @Test
  void a_file_whose_mtime_falls_behind_the_watermark_has_disappeared_as_the_listing_said() {
    var source = new SeekableFakeSource();
    source.put("/in/first.csv", csv(1, 10), T1);
    source.put("/in/big.csv", csv(1, 300), T2);
    // read 1 exhausts first.csv (watermark := T1); read 2 starts big.csv.
    var r1 = (ReadResult.HasMore<Map<String, Object>>) reader(source, "leave").read(100, ConnectorCursor.empty(), ctx());
    var r2 = (ReadResult.HasMore<Map<String, Object>>) reader(source, "leave").read(100, r1.nextCursor(), ctx());
    assertThat(r2.nextCursor().value().path("inProgress").path("path").asText()).isEqualTo("/in/big.csv");

    // Same bytes, mtime pushed before the watermark: listSince(T1) no longer returns it.
    source.put("/in/big.csv", csv(1, 300), T1.minusSeconds(60));

    assertThatThrownBy(() -> reader(source, "leave").read(100, r2.nextCursor(), ctx()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("/in/big.csv")
        .hasMessageContaining("disappeared mid-ingestion");
  }

  /** The cursor after the first chunk of the only file in {@code source}: a file in progress. */
  private ConnectorCursor firstChunkCursor(FakeSource source) {
    var r1 = (ReadResult.HasMore<Map<String, Object>>) reader(source, "leave").read(100, ConnectorCursor.empty(), ctx());
    assertThat(r1.nextCursor().value().path("inProgress").isMissingNode()).isFalse();
    return r1.nextCursor();
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
      ReadResult<Map<String, Object>> res = r.read(100, cursor, ctx());
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
      var res = (ReadResult.HasMore<Map<String, Object>>) reader(source, "delete").read(100, cursor, ctx());
      assertThat(res.records()).hasSize(expected);
      assertThat(res.records()).allSatisfy(row -> assertThat(row).containsKey("id"));
      cursor = res.nextCursor();
      assertThat(source.marked)
          .as("a file must never be archived before its final chunk's cursor is persisted")
          .isEmpty();
    }

    // b.csv's first read: the persisted cursor now proves a.csv committed → archive it first.
    var res = (ReadResult.HasMore<Map<String, Object>>) reader(source, "delete").read(100, cursor, ctx());
    assertThat(source.marked).containsExactly("/in/a.csv");
    assertThat(res.records()).hasSize(50);
    // Chunks never span two files: this chunk is pure b.csv even though 50 < chunkSize.
    assertThat(res.records().get(0).get("name")).isEqualTo("row-1");
    cursor = res.nextCursor();

    // Final read: b.csv archived, nothing new → EndOfStream.
    var eos = reader(source, "delete").read(100, cursor, ctx());
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

  // -------------------------------------------------------------------------
  // 7. Transport cost per chunk (#898, #1017): a listing per run, a stat per file taken and per resumed chunk
  // -------------------------------------------------------------------------

  @Test
  void a_run_is_listed_twice_then_checks_each_file_taken_and_each_resumed_chunk_with_one_stat() {
    var source = new SeekableFakeSource();
    source.put("/in/big.csv", csv(1, 1000), T1);
    source.put("/in/next.csv", csv(1, 10), T2);

    int starts = 0;
    int resumes = 0;
    ConnectorCursor cursor = ConnectorCursor.empty();
    while (true) {
      if (cursor.isEmpty() || cursor.value().path("inProgress").isMissingNode()) starts++;
      else resumes++;
      var res = reader(source, "leave").read(100, cursor, ctx());
      if (!(res instanceof ReadResult.HasMore<Map<String, Object>> hm)) break;
      cursor = hm.nextCursor();
    }

    assertThat(resumes).isGreaterThanOrEqualTo(9);
    assertThat(source.lists)
        .as("%d file starts: one listing finds the files, one at the end sees what arrived meanwhile with an mtime not behind the watermark", starts)
        .isEqualTo(2);
    assertThat(source.stats)
        .as("each resumed chunk re-checks the in-progress file with ONE stat (%d), and the second file taken"
            + " from the run's list is checked once before it starts", resumes)
        .isEqualTo(resumes + 1);
  }

  // -------------------------------------------------------------------------
  // The source belongs to the run, not to the chunk (#898)
  // -------------------------------------------------------------------------

  @Test
  void a_file_of_several_chunks_builds_its_source_once_and_closes_it_with_the_run() {
    var source = new SeekableFakeSource();
    source.put("/in/big.csv", csv(1, 1000), T1);
    var registry = new CountingRegistry(source);

    assertConservedInOrder(drain(registry, 100, chunk -> { }).rows(), 1000);

    assertThat(registry.builds).as("ten chunks, one build").isEqualTo(1);
    assertThat(source.closes).as("the run is not over: the reader must not close what the run owns").isZero();
    scope.close();
    assertThat(source.closes).as("the scope closes it at the end").isEqualTo(1);
  }

  @Test
  void several_files_in_a_run_share_the_one_source() {
    var source = new SeekableFakeSource();
    source.put("/in/a.csv", csv(1, 50), T1);
    source.put("/in/b.csv", csv(51, 100), T2);
    var registry = new CountingRegistry(source);

    // archive is `leave`, so b.csv is picked up after a.csv by the watermark
    assertThat(drain(registry, 100, chunk -> { }).rows()).as("both files were read").hasSize(100);
    assertThat(registry.builds).isEqualTo(1);
  }

  @Test
  void a_source_that_is_no_longer_usable_is_replaced_once_before_the_chunk_starts() {
    var dying = new SeekableFakeSource();
    var fresh = new SeekableFakeSource();
    dying.put("/in/big.csv", csv(1, 300), T1);
    fresh.put("/in/big.csv", csv(1, 300), T1);
    var registry = new CountingRegistry(dying, fresh);

    List<Map<String, Object>> rows = drain(registry, 100, chunk -> {
      if (chunk == 1) dying.usable = false;      // the session died while the previous chunk was being written
    }).rows();

    assertConservedInOrder(rows, 300);
    assertThat(registry.builds).as("one replacement, not one build per chunk").isEqualTo(2);
    assertThat(dying.closes).as("the dead source is closed at once, not at the end of the run").isEqualTo(1);
    assertThat(fresh.closes).isZero();
  }

  @Test
  void a_replacement_that_is_also_unusable_is_used_anyway_and_fails_with_its_own_error() {
    var dying = new FakeSource();
    dying.usable = false;
    var broken = new FakeSource() {
      @Override public InputStream open(FileDescriptor file) { throw new IllegalStateException("boom"); }
    };
    broken.usable = false;
    broken.put("/in/a.csv", csv(1, 10), T1);
    var registry = new CountingRegistry(dying, broken);

    assertThatThrownBy(() -> reader(registry, "leave").read(100, ConnectorCursor.empty(), ctx()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

    assertThat(registry.builds).as("rebuilt once, never in a loop").isEqualTo(2);
  }

  @Test
  void a_source_that_turns_unusable_in_the_middle_of_a_chunk_is_not_retried() {
    var source = new FakeSource() {
      @Override public InputStream open(FileDescriptor file) {
        usable = false;
        throw new IllegalStateException("mid-chunk");
      }
    };
    source.put("/in/a.csv", csv(1, 10), T1);
    var registry = new CountingRegistry(source);

    assertThatThrownBy(() -> reader(registry, "delete").read(100, ConnectorCursor.empty(), ctx()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("mid-chunk");

    assertThat(registry.builds).as("no second build inside a chunk").isEqualTo(1);
    assertThat(source.marked).as("nothing was archived or deleted, so nothing can be replayed").isEmpty();
  }

  @Test
  void a_failure_after_the_archive_step_does_not_replay_the_archive() {
    var source = new FakeSource() {
      @Override public InputStream open(FileDescriptor file) {
        if (file.path().equals("/in/b.csv")) throw new IllegalStateException("b.csv went away");
        return super.open(file);
      }
    };
    source.put("/in/a.csv", csv(1, 10), T1);
    source.put("/in/b.csv", csv(11, 20), T2);
    var registry = new CountingRegistry(source);

    // chunk 1 returns a.csv's rows; its cursor now proves a.csv committed, so chunk 2 deletes it, then fails on b.csv
    var first = (ReadResult.HasMore<Map<String, Object>>) reader(registry, "delete").read(100, ConnectorCursor.empty(), ctx());
    assertThat(first.records()).hasSize(10);
    assertThatThrownBy(() -> reader(registry, "delete").read(100, first.nextCursor(), ctx()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("b.csv went away");

    assertThat(source.marked).as("a.csv deleted exactly once: the failing chunk is not replayed").containsExactly("/in/a.csv");
    assertThat(registry.builds).isEqualTo(1);
  }

  /** #1452: a read that begins a file says so; one that continues it does not — a carrying mapping resets on it. */
  @Test
  void a_read_says_whether_it_starts_a_file() {
    var source = new SeekableFakeSource();
    source.put("/in/a.csv", csv(1, 150), T1);
    source.put("/in/b.csv", csv(151, 160), T2);

    FileSourceCursorReader first = reader(source, "leave");
    ReadResult<Map<String, Object>> r1 = first.read(100, ConnectorCursor.empty(), ctx());
    FileSourceCursorReader second = reader(source, "leave");
    ReadResult<Map<String, Object>> r2 = second.read(100, ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor(), ctx());
    FileSourceCursorReader third = reader(source, "leave");
    third.read(100, ((ReadResult.HasMore<Map<String, Object>>) r2).nextCursor(), ctx());

    assertThat(first.startsAFile()).as("a.csv, rows 1-100").isTrue();
    assertThat(second.startsAFile()).as("a.csv, rows 101-150").isFalse();
    assertThat(third.startsAFile()).as("b.csv").isTrue();
  }
}
