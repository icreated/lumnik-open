package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.mapping.carry.StaleCarryException;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1452: a mapping that carries values row to row is reset at each file and cannot see where one ends inside a chunk —
 * the local reader then hands it one file per chunk, and says when a chunk begins a file.
 */
class CsvCarryChunkingTest {

  private static CsvFileConnectorType.ParserOverrides overrides() {
    return CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true, "delimiter", ","));
  }

  /** With a carrying mapping, two small files make two chunks, each starting its file; without one, one chunk. */
  @Test
  void a_carrying_mapping_reads_one_file_per_chunk(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.csv"), "row_no,f\n1,a\n2,a\n");
    Files.writeString(dir.resolve("b.csv"), "row_no,f\n1,b\n");
    String glob = dir.resolve("*.csv").toString();

    var carrying = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    ReadResult<Map<String, Object>> first = carrying.read(500, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    assertThat(records(first)).extracting(r -> r.get("f")).containsOnly("a");
    assertThat(carrying.startsAFile()).isTrue();

    var next = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    ReadResult<Map<String, Object>> second =
        next.read(500, ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor(), ReaderTestContexts.newRun());
    assertThat(records(second)).extracting(r -> r.get("f")).containsOnly("b");
    assertThat(next.startsAFile()).isTrue();

    var plain = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, false);
    assertThat(records(plain.read(500, ConnectorCursor.empty(), ReaderTestContexts.newRun()))).hasSize(3);
  }

  /**
   * A file that gives no row (header only) does not end a chunk empty: an empty chunk reads as the end of the run, and
   * the files after it would stay unread under a "Completed" run. The chunk goes on into the next file, and starts it.
   */
  @Test
  void a_file_with_no_row_does_not_end_the_chunk_empty(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.csv"), "row_no,f\n1,a\n");
    Files.writeString(dir.resolve("b.csv"), "row_no,f\n");
    Files.writeString(dir.resolve("c.csv"), "row_no,f\n1,c\n");
    String glob = dir.resolve("*.csv").toString();

    var first = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    ReadResult<Map<String, Object>> r1 = first.read(500, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    var second = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    ReadResult<Map<String, Object>> r2 =
        second.read(500, ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor(), ReaderTestContexts.newRun());

    assertThat(records(r2)).extracting(r -> r.get("f")).containsExactly("c");
    assertThat(second.startsAFile()).isTrue();
  }

  /**
   * A file replaced between two chunks: its carried values describe rows that no longer exist, and the position counts
   * rows of other bytes. A carrying mapping refuses to resume it — on the checkpoint path (UTF-8) and on the fallback,
   * which resumes by row count (IBM437 is not checkpointable).
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"UTF-8", "IBM437"})
  void a_file_changed_between_chunks_is_not_resumed(String encoding, @TempDir Path dir) throws Exception {
    Path a = dir.resolve("a.csv");
    Files.writeString(a, "row_no,f\n1,a\n2,a\n3,a\n");
    String glob = dir.resolve("*.csv").toString();
    var parser = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true, "delimiter", ",", "encoding", encoding));
    ReadResult<Map<String, Object>> first = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true)
        .read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());

    Files.writeString(a, "row_no,f\n1,x\n2,x\n3,x\n4,x\n");
    var resumed = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true);
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();

    assertThatThrownBy(() -> resumed.read(2, cursor, ReaderTestContexts.newRun()))
        .isInstanceOf(StaleCarryException.class)
        .hasMessageContaining("a.csv").hasMessageContaining("lm endpoint reset-cursor <connector>/<endpoint>");
  }

  /**
   * A replacement of the same length that keeps the modification time (`cp -p`): size and mtime match, the bytes do
   * not. The witness is the content's fingerprint, so it is refused too, on both paths.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"UTF-8", "IBM437"})
  void a_same_size_same_mtime_replacement_is_not_resumed(String encoding, @TempDir Path dir) throws Exception {
    Path a = dir.resolve("a.csv");
    Files.writeString(a, "row_no,f\n1,a\n2,a\n3,a\n");
    FileTime mtime = Files.getLastModifiedTime(a);
    String glob = dir.resolve("*.csv").toString();
    var parser = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true, "delimiter", ",", "encoding", encoding));
    ReadResult<Map<String, Object>> first = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true)
        .read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());

    Files.writeString(a, "row_no,f\n1,x\n2,x\n3,x\n");
    Files.setLastModifiedTime(a, mtime);
    var resumed = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true);
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();

    assertThatThrownBy(() -> resumed.read(2, cursor, ReaderTestContexts.newRun()))
        .isInstanceOf(StaleCarryException.class).hasMessageContaining("lm endpoint reset-cursor");
  }

  /**
   * The same replacement between two chunks of ONE run — the scope the engine holds for the whole run, whose content
   * fingerprints are kept: the kept fingerprint must not vouch for bytes it was not taken from.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"UTF-8", "IBM437"})
  void a_same_size_same_mtime_replacement_within_one_run_is_not_resumed(String encoding, @TempDir Path dir)
      throws Exception {
    Path a = dir.resolve("a.csv");
    Files.writeString(a, "row_no,f\n1,a\n2,a\n3,a\n");
    FileTime mtime = Files.getLastModifiedTime(a);
    String glob = dir.resolve("*.csv").toString();
    var parser = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true, "delimiter", ",", "encoding", encoding));
    try (OwnedRunScope run = new OwnedRunScope("test")) {
      ReadResult<Map<String, Object>> first = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true)
          .read(2, ConnectorCursor.empty(), ReaderTestContexts.of(run));

      Files.writeString(a, "row_no,f\n1,x\n2,x\n3,x\n");
      Files.setLastModifiedTime(a, mtime);
      var next = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true);
      ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();

      assertThatThrownBy(() -> next.read(2, cursor, ReaderTestContexts.of(run)))
          .isInstanceOf(StaleCarryException.class).hasMessageContaining("lm endpoint reset-cursor");
    }
  }

  /**
   * Unchanged, within one run: every chunk resumes, and the file is hashed once for the run — the fingerprint is kept
   * under the file's identity, not taken again per chunk.
   */
  @Test
  void an_unchanged_file_within_one_run_is_resumed_and_hashed_once(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.csv"), "row_no,f\n1,a\n2,a\n3,a\n4,a\n5,a\n6,a\n7,a\n");
    String glob = dir.resolve("*.csv").toString();
    try (OwnedRunScope owned = new OwnedRunScope("test")) {
      CountingRunScope run = new CountingRunScope(owned);
      List<Object> read = new ArrayList<>();
      ConnectorCursor cursor = ConnectorCursor.empty();
      for (int chunk = 0; chunk < 3; chunk++) {
        ReadResult<Map<String, Object>> r = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true)
            .read(2, cursor, ReaderTestContexts.of(run));
        records(r).forEach(row -> read.add(row.get("row_no")));
        cursor = ((ReadResult.HasMore<Map<String, Object>>) r).nextCursor();
      }
      assertThat(read).containsExactly("1", "2", "3", "4", "5", "6");
      assertThat(run.builds(CsvFileConnectorType.ContentKey.class)).as("one hash for the run").isEqualTo(1);
    }
  }

  /** The same file unchanged resumes, on both paths: the witness refuses only what changed. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"UTF-8", "IBM437"})
  void an_unchanged_file_is_resumed(String encoding, @TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.csv"), "row_no,f\n1,a\n2,a\n3,a\n");
    String glob = dir.resolve("*.csv").toString();
    var parser = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true, "delimiter", ",", "encoding", encoding));
    ReadResult<Map<String, Object>> first = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true)
        .read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    ReadResult<Map<String, Object>> second = new CsvFileConnectorType.CsvGlobCursorReader(glob, parser, false, true)
        .read(2, ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor(), ReaderTestContexts.newRun());

    assertThat(records(second)).extracting(r -> r.get("row_no")).containsExactly("3");
  }

  /** A chunk that resumes mid-file does not start it. */
  @Test
  void a_chunk_resumed_mid_file_does_not_start_it(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.csv"), "row_no,f\n1,a\n2,a\n3,a\n");
    String glob = dir.resolve("*.csv").toString();
    var r1 = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    ReadResult<Map<String, Object>> first = r1.read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    var r2 = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides(), false, true);
    r2.read(2, ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor(), ReaderTestContexts.newRun());
    assertThat(r1.startsAFile()).isTrue();
    assertThat(r2.startsAFile()).isFalse();
  }

  private static List<Map<String, Object>> records(ReadResult<Map<String, Object>> r) {
    return switch (r) {
      case ReadResult.HasMore<Map<String, Object>> hm -> hm.records();
      case ReadResult.EndOfStream<Map<String, Object>> eos -> eos.records();
      case ReadResult.EmptyButOpen<Map<String, Object>> ep -> List.of();
    };
  }
}
