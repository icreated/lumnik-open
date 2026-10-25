package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #898 (d): a local run lists its folder once, at its first chunk, and every later chunk of the run reads the same
 * list. The engine builds a fresh reader per chunk, so each test does the same: one reader per {@code read}.
 */
class CsvGlobListingScopeTest {

  private static final CsvFileConnectorType.ParserOverrides DEFAULTS =
      new CsvFileConnectorType.ParserOverrides(',', StandardCharsets.UTF_8,
          true, true, List.of("", "NULL"), null);

  @TempDir Path dir;
  private final List<OwnedRunScope> opened = new ArrayList<>();
  private CountingRunScope scope;

  @BeforeEach
  void freshRunScope() {
    scope = run("test");
  }

  @AfterEach
  void closeRunScopes() {
    opened.forEach(OwnedRunScope::close);
  }

  /** A new run's scope, closed with the test. */
  private CountingRunScope run(String name) {
    OwnedRunScope owned = new OwnedRunScope(name);
    opened.add(owned);
    return new CountingRunScope(owned);
  }

  /** Rows read so far, and the last cursor the run left behind (a {@code HasMore}'s, or an {@code EndOfStream}'s final one). */
  private record Drained(List<String> ids, ConnectorCursor lastCursor, int chunks) {}

  private void file(String name, int from, int count) throws IOException {
    StringBuilder sb = new StringBuilder("id\n");
    for (int i = from; i < from + count; i++) sb.append(name).append('-').append(i).append('\n');
    Files.writeString(dir.resolve(name), sb.toString());
  }

  private String glob() {
    return dir.resolve("*.csv").toString();
  }

  /** One chunk with a fresh reader, as {@code ChunkExecutor} does. */
  private ReadResult<Map<String, Object>> readChunk(String pattern, CountingRunScope s, int size, ConnectorCursor cursor) {
    return new CsvFileConnectorType.CsvGlobCursorReader(pattern, DEFAULTS).read(size, cursor, ReaderTestContexts.of(s));
  }

  private Drained drain(String pattern, CountingRunScope s, int size, ConnectorCursor from, int maxChunks) {
    return drain(pattern, () -> s, size, from, maxChunks);
  }

  /** The run's chunks, each read through the scope {@code scopes} hands out (the same one, for a real run). */
  private Drained drain(String pattern, Supplier<CountingRunScope> scopes, int size, ConnectorCursor from,
                        int maxChunks) {
    List<String> ids = new ArrayList<>();
    ConnectorCursor cursor = from;
    int chunks = 0;
    while (chunks < maxChunks) {
      ReadResult<Map<String, Object>> r = readChunk(pattern, scopes.get(), size, cursor);
      chunks++;
      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        hm.records().forEach(row -> ids.add(String.valueOf(row.get("id"))));
        cursor = hm.nextCursor();
        if (hm.records().isEmpty()) break;
      } else {
        var eos = (ReadResult.EndOfStream<Map<String, Object>>) r;
        eos.records().forEach(row -> ids.add(String.valueOf(row.get("id"))));
        if (!eos.finalCursor().isEmpty()) cursor = eos.finalCursor();
        break;
      }
    }
    return new Drained(ids, cursor, chunks);
  }

  @Test
  void a_run_over_many_chunks_and_files_lists_the_folder_once() throws Exception {
    for (String n : List.of("a.csv", "b.csv", "c.csv", "d.csv", "e.csv", "f.csv")) file(n, 0, 10);

    Drained run = drain(glob(), scope, 4, ConnectorCursor.empty(), 100);

    assertThat(run.ids()).hasSize(60);
    assertThat(run.ids().get(0)).isEqualTo("a.csv-0");
    assertThat(run.ids().get(59)).isEqualTo("f.csv-9");
    assertThat(run.chunks()).as("several chunks").isGreaterThanOrEqualTo(15);
    assertThat(scope.builds()).as("one listing for the whole run").isEqualTo(1);
  }

  @Test
  void a_file_dropped_after_the_first_chunk_waits_for_the_next_run() throws Exception {
    file("a.csv", 0, 10);
    file("b.csv", 0, 10);

    ReadResult<Map<String, Object>> first = readChunk(glob(), scope, 4, ConnectorCursor.empty());
    file("zzz.csv", 0, 3);   // dropped while the run is going
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();
    List<String> ids = new ArrayList<>(
        ((ReadResult.HasMore<Map<String, Object>>) first).records().stream().map(r -> String.valueOf(r.get("id"))).toList());
    Drained rest = drain(glob(), scope, 4, cursor, 100);
    ids.addAll(rest.ids());

    assertThat(ids).as("the run reads the folder as it stood at its first chunk").hasSize(20)
        .noneMatch(id -> id.startsWith("zzz"));
    assertThat(scope.builds()).isEqualTo(1);

    // The closing EndOfStream hands over the finished position: the next run starts there, reads
    // no tail again, and reaches zzz.csv.
    CountingRunScope next = run("next-run");
    Drained nextRun = drain(glob(), next, 4, rest.lastCursor(), 100);

    assertThat(nextRun.ids()).as("every dropped row arrives exactly once, and nothing before it is read again")
        .containsExactly("zzz.csv-0", "zzz.csv-1", "zzz.csv-2");
    // one listing; the next run also counts b.csv as unchanged (#1371), a resource of its own
    assertThat(next.builds(CsvFileConnectorType.LocalListingKey.class)).isEqualTo(1);
  }

  @Test
  void a_file_that_lands_between_two_files_of_the_list_is_not_read_by_this_run_nor_by_the_next() throws Exception {
    file("a.csv", 0, 10);
    file("c.csv", 0, 10);

    ReadResult<Map<String, Object>> first = readChunk(glob(), scope, 4, ConnectorCursor.empty());
    file("b.csv", 0, 3);   // sorts after the cursor's file (a) and before the next one (c)
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();
    Drained rest = drain(glob(), scope, 4, cursor, 100);

    // The accepted cost of a frozen listing, documented in csv.md: the next run resumes from where this one
    // ended, past b, so b is never read. (Before the listing was held, a later chunk would have listed it.)
    CountingRunScope next = run("next-run");
    Drained nextRun = drain(glob(), next, 4, rest.lastCursor(), 100);
    assertThat(rest.ids()).noneMatch(id -> id.startsWith("b.csv"));
    assertThat(nextRun.ids()).noneMatch(id -> id.startsWith("b.csv"));
  }

  @Test
  void a_file_of_the_snapshot_that_disappears_fails_the_run_naming_it() throws Exception {
    file("a.csv", 0, 10);
    file("b.csv", 0, 10);
    file("c.csv", 0, 10);

    ReadResult<Map<String, Object>> first = readChunk(glob(), scope, 4, ConnectorCursor.empty());
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();
    Files.delete(dir.resolve("b.csv"));   // a file of the snapshot, not yet reached

    assertThatThrownBy(() -> drain(glob(), scope, 4, cursor, 100))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to read CSV")
        .hasMessageContaining("b.csv");
  }

  @Test
  void a_run_resumes_from_a_cursor_in_the_middle_of_the_list() throws Exception {
    for (String n : List.of("a.csv", "b.csv", "c.csv", "d.csv")) file(n, 0, 10);
    List<String> whole = drain(glob(), run("whole"), 4,
        ConnectorCursor.empty(), 100).ids();

    // Three chunks, then "the run ends" with the cursor of the last HasMore.
    Drained firstRun = drain(glob(), scope, 4, ConnectorCursor.empty(), 3);
    CountingRunScope second = run("second-run");
    Drained secondRun = drain(glob(), second, 4, firstRun.lastCursor(), 100);

    List<String> joined = new ArrayList<>(firstRun.ids());
    joined.addAll(secondRun.ids());
    assertThat(joined).as("no gap, no duplicate, same order").isEqualTo(whole);
    assertThat(second.builds()).as("the second run lists once").isEqualTo(1);
  }

  @Test
  void the_cursor_is_compatible_across_artificial_runs_of_one_chunk_each() throws Exception {
    for (String n : List.of("a.csv", "b.csv", "c.csv")) file(n, 0, 10);
    List<String> whole = drain(glob(), scope, 4, ConnectorCursor.empty(), 100).ids();

    // NOT a production shape: a new scope per chunk lists once per chunk by construction. It pins that the
    // cursor alone carries the position between listings, as it does between real runs.
    List<String> ids = drain(glob(), () -> run("one-chunk"), 4, ConnectorCursor.empty(), 100).ids();
    assertThat(ids).isEqualTo(whole);
  }

  @Test
  void a_cursor_file_that_is_gone_while_later_files_wait_fails_the_run_naming_it() throws Exception {
    file("a.csv", 0, 10);
    file("b.csv", 0, 10);
    file("c.csv", 0, 10);
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>)
        readChunk(glob(), scope, 4, ConnectorCursor.empty())).nextCursor();
    Files.delete(dir.resolve("a.csv"));   // archived between runs, while b and c wait

    CountingRunScope next = run("next-run");

    assertThatThrownBy(() -> readChunk(glob(), next, 4, cursor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(dir.resolve("a.csv").toString())
        .hasMessageContaining("reset-cursor");
  }

  @Test
  void a_cursor_file_that_is_gone_with_nothing_after_it_ends_the_run_quietly() throws Exception {
    file("a.csv", 0, 10);
    file("b.csv", 0, 10);
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>)
        readChunk(glob(), scope, 15, ConnectorCursor.empty())).nextCursor();   // cursor sits in b
    Files.delete(dir.resolve("b.csv"));   // the last file, archived

    ReadResult<Map<String, Object>> r = readChunk(glob(), run("next-run"), 4, cursor);

    assertThat(r).isInstanceOf(ReadResult.EndOfStream.class);
    assertThat(((ReadResult.EndOfStream<Map<String, Object>>) r).records()).isEmpty();
  }

  @Test
  void the_last_chunk_of_a_run_hands_over_the_finished_position() throws Exception {
    file("a.csv", 0, 12);

    ConnectorCursor cursor = ConnectorCursor.empty();
    for (int i = 0; i < 2; i++) {
      cursor = ((ReadResult.HasMore<Map<String, Object>>) readChunk(glob(), run("r" + i), 5, cursor)).nextCursor();
    }
    var last = (ReadResult.EndOfStream<Map<String, Object>>) readChunk(glob(), run("last"), 5, cursor);

    assertThat(last.records()).hasSize(2);
    var finished = CsvCursor.fromJson(last.finalCursor().value().toString());
    assertThat(finished.file()).isEqualTo(dir.resolve("a.csv").toString());
    assertThat(finished.rowsProcessed()).isEqualTo(12);
    assertThat(finished.fullyRead()).isTrue();
    // a later run over the same folder reads nothing and leaves that position alone
    var again = (ReadResult.EndOfStream<Map<String, Object>>) readChunk(glob(), run("again"), 5, last.finalCursor());
    assertThat(again.records()).isEmpty();
    assertThat(again.finalCursor().isEmpty()).isTrue();
  }

  @Test
  void a_fully_read_file_may_be_archived_and_the_next_one_is_read() throws Exception {
    file("a.csv", 0, 12);
    ConnectorCursor cursor = drain(glob(), scope, 5, ConnectorCursor.empty(), 100).lastCursor();
    Files.delete(dir.resolve("a.csv"));   // archived after its run completed
    file("b.csv", 0, 3);

    Drained next = drain(glob(), run("next-run"), 5, cursor, 100);

    assertThat(next.ids()).containsExactly("b.csv-0", "b.csv-1", "b.csv-2");
  }

  @Test
  void a_fully_read_file_that_is_unchanged_is_skipped_without_parsing_it() throws Exception {
    file("a.csv", 0, 12);
    ConnectorCursor cursor = drain(glob(), scope, 5, ConnectorCursor.empty(), 100).lastCursor();

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(glob(), DEFAULTS);
    var r = (ReadResult.EndOfStream<Map<String, Object>>) reader.read(5, cursor, ReaderTestContexts.of(run("next-run")));

    assertThat(r.records()).isEmpty();
    assertThat(reader.rowsTokenized()).as("the file is not opened").isZero();
  }

  @Test
  void a_fully_read_file_that_grew_or_was_replaced_is_read_again_in_full() throws Exception {
    file("a.csv", 0, 12);
    ConnectorCursor cursor = drain(glob(), scope, 5, ConnectorCursor.empty(), 100).lastCursor();

    file("a.csv", 0, 15);   // three rows appended
    assertThat(drain(glob(), run("grown"), 5, cursor, 100).ids()).hasSize(15);

    file("a.csv", 100, 4);  // replaced by a shorter, different file
    assertThat(drain(glob(), run("replaced"), 5, cursor, 100).ids())
        .containsExactly("a.csv-100", "a.csv-101", "a.csv-102", "a.csv-103");
  }

  @Test
  void the_cursors_checkpoint_is_never_offered_to_the_next_file_of_the_walk() throws Exception {
    file("a.csv", 0, 10);
    file("b.csv", 0, 10);   // same size as a.csv ...
    var sameInstant = FileTime.fromMillis(1_700_000_000_000L);
    Files.setLastModifiedTime(dir.resolve("a.csv"), sameInstant);
    Files.setLastModifiedTime(dir.resolve("b.csv"), sameInstant);   // ... and the same version token
    var first = (ReadResult.HasMore<Map<String, Object>>) readChunk(glob(), scope, 4, ConnectorCursor.empty());
    assertThat(CsvCursor.fromJson(first.nextCursor().value().toString()).hasCheckpoint()).isTrue();

    // a's last six rows, then b from its start: b must not be resumed at a's anchor
    Drained rest = drain(glob(), run("next"), 12, first.nextCursor(), 100);

    assertThat(rest.ids()).containsExactly("a.csv-4", "a.csv-5", "a.csv-6", "a.csv-7", "a.csv-8", "a.csv-9",
        "b.csv-0", "b.csv-1", "b.csv-2", "b.csv-3", "b.csv-4", "b.csv-5", "b.csv-6", "b.csv-7", "b.csv-8", "b.csv-9");
  }

  @Test
  void a_plain_path_is_a_one_file_list_and_a_missing_one_is_empty() throws Exception {
    file("only.csv", 0, 3);

    Drained plain = drain(dir.resolve("only.csv").toString(), scope, 2, ConnectorCursor.empty(), 100);
    assertThat(plain.ids()).containsExactly("only.csv-0", "only.csv-1", "only.csv-2");
    assertThat(scope.builds()).isEqualTo(1);

    CountingRunScope other = run("missing");
    ReadResult<Map<String, Object>> none = readChunk(dir.resolve("absent.csv").toString(), other, 2, ConnectorCursor.empty());
    assertThat(none).isInstanceOf(ReadResult.EndOfStream.class);
    assertThat(other.builds()).isEqualTo(1);
  }

  @Test
  void a_listing_failure_keeps_todays_message_and_is_retried_by_the_next_ask() throws Exception {
    Path later = dir.resolve("later");
    String pattern = later.resolve("*.csv").toString();

    assertThatThrownBy(() -> readChunk(pattern, scope, 4, ConnectorCursor.empty()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to resolve path pattern: " + pattern)
        .hasCauseInstanceOf(IOException.class);

    Files.createDirectory(later);
    Files.writeString(later.resolve("x.csv"), "id\n1\n");
    ReadResult<Map<String, Object>> r = readChunk(pattern, scope, 4, ConnectorCursor.empty());

    assertThat(r).isInstanceOf(ReadResult.EndOfStream.class);
    assertThat(((ReadResult.EndOfStream<Map<String, Object>>) r).records()).hasSize(1);
    assertThat(scope.builds()).as("the failed attempt and the good one").isEqualTo(2);
  }
}
