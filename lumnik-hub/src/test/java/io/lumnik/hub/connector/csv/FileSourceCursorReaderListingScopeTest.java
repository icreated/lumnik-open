package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.csv.FileSourceCursorReaderChunkingTest.FakeSource;
import io.lumnik.hub.connector.csv.FileSourceCursorReaderChunkingTest.SeekableFakeSource;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1017: a remote run lists its source for the files to read once, not once per file. The reader is rebuilt per
 * chunk, so the list lives in the run scope; each file taken from it is re-checked with one {@code stat}, and a
 * list that went stale (file gone, file changed) is dropped or rebuilt rather than trusted.
 */
class FileSourceCursorReaderListingScopeTest {

  private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");

  private static String csv(int rows) {
    StringBuilder sb = new StringBuilder("id,name\n");
    for (int i = 1; i <= rows; i++) sb.append(i).append(",r").append(i).append('\n');
    return sb.toString();
  }

  private static String name(int i) {
    return String.format("/in/f%04d.csv", i);
  }

  private static FileSourceCursorReader reader(FakeSource source, String afterProcess) {
    Map<String, Object> parser = Map.of("encoding", "UTF-8", "delimiter", ",", "has_header", true);
    Map<String, Object> config = Map.of("transport", Map.of("kind", "sftp"),
        "after_process", afterProcess, "parser", parser);
    FileSourceRegistry registry = new FileSourceRegistry() {
      @Override public FileSource build(Map<String, Object> transportCfg) { return source; }
    };
    return new FileSourceCursorReader(config, CsvFileConnectorType.buildParserOverrides(parser), registry);
  }

  /** One run: one scope, a fresh reader per chunk. Returns the ids of the files read, in order. */
  private static List<String> run(FakeSource source, String afterProcess, IntConsumer afterChunk) {
    List<String> readFrom = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    int chunk = 0;
    try (OwnedRunScope scope = new OwnedRunScope("test")) {
      while (true) {
        ReadResult<Map<String, Object>> res =
            reader(source, afterProcess).read(100, cursor, ReaderTestContexts.of(scope));
        if (!(res instanceof ReadResult.HasMore<Map<String, Object>> hm)) {
          var eos = (ReadResult.EndOfStream<Map<String, Object>>) res;
          eos.records().forEach(r -> readFrom.add(String.valueOf(r.get("file"))));
          return readFrom;
        }
        hm.records().forEach(r -> readFrom.add(String.valueOf(r.get("file"))));
        cursor = hm.nextCursor();
        afterChunk.accept(++chunk);
      }
    }
  }

  private static SeekableFakeSource tinyFiles(int n) {
    var source = new SeekableFakeSource();
    for (int i = 0; i < n; i++) source.put(name(i), "file,id\n" + name(i) + "," + i + "\n", T0.plusSeconds(i));
    return source;
  }

  @Test
  void a_run_over_many_files_lists_the_source_twice_not_once_per_file() {
    var source = tinyFiles(50);

    List<String> files = run(source, "delete", c -> { });

    assertThat(files).hasSize(50).isSorted();
    assertThat(source.lists)
        .as("one listing to find the files, one to see that nothing arrived while the run read them")
        .isEqualTo(2);
  }

  @Test
  void a_long_run_lists_again_every_so_many_files_so_a_list_is_never_trusted_for_the_whole_run() {
    int n = 2 * FileSourceCursorReader.REFRESH_EVERY + 50;
    var source = tinyFiles(n);

    List<String> files = run(source, "delete", c -> { });

    assertThat(files).hasSize(n).isSorted();
    assertThat(source.lists)
        .as("listings at the start, after each %d files, and at the end", FileSourceCursorReader.REFRESH_EVERY)
        .isEqualTo(4);
  }

  @Test
  void a_file_that_left_the_source_since_the_listing_is_skipped_not_fatal() {
    var source = tinyFiles(5);

    List<String> files = run(source, "leave", chunk -> { if (chunk == 1) source.remove(name(2)); });

    assertThat(files).containsExactly(name(0), name(1), name(3), name(4));
  }

  @Test
  void a_file_that_changed_since_the_listing_is_read_in_the_order_a_fresh_listing_gives() {
    var source = tinyFiles(4);   // f0..f3 at T0..T0+3

    // After f0 is read, f1 is rewritten with an mtime between f2 and f3: a fresh listing puts it there. Skipping it,
    // or reading it where the old list had it, loses it to the watermark.
    List<String> files = run(source, "leave", chunk -> {
      if (chunk == 1) source.put(name(1), "file,id\n" + name(1) + ",1\n", T0.plusMillis(2500));
    });

    assertThat(files).containsExactly(name(0), name(2), name(1), name(3));
  }

  @Test
  void a_file_that_arrives_while_the_run_reads_is_picked_up_by_the_same_run() {
    var source = tinyFiles(3);

    List<String> files = run(source, "leave", chunk -> {
      if (chunk == 1) source.put(name(9), "file,id\n" + name(9) + ",9\n", T0.plusSeconds(500));
    });

    assertThat(files).containsExactly(name(0), name(1), name(2), name(9));
  }
}
