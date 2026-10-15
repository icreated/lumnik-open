package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvGlobCursorReaderMalformedTest {

  private static final CsvFileConnectorType.ParserOverrides DEFAULTS =
      new CsvFileConnectorType.ParserOverrides(',', StandardCharsets.UTF_8,
          true, true, List.of("", "NULL", "null", "#N/A"), null);

  @Test
  void single_chunk_isolates_bad_row_and_drains_it(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("data.csv");
    Files.writeString(file, "a,b,c\n1,2,3\nONLY_ONE\n7,8,9\n");

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    ReadResult<Map<String, Object>> r = reader.read(500, ConnectorCursor.empty(), ReaderTestContexts.newRun());

    List<Map<String, Object>> good = recordsOf(r);
    assertThat(good).hasSize(2);
    List<MalformedRecord> bad = reader.drainMalformed();
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).line()).isEqualTo(3L);
    assertThat(reader.drainMalformed()).isEmpty();
  }

  @Test
  void spanning_chunks_do_not_double_count_a_bad_row(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("span.csv");
    // events in order: G,G,BAD,G,G   (chunkSize=2 -> multiple reads)
    Files.writeString(file, "a,b,c\n1,1,1\n2,2,2\nONLY_ONE\n4,4,4\n5,5,5\n");

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);

    List<Map<String, Object>> allGood = new ArrayList<>();
    List<MalformedRecord> allBad = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    for (int i = 0; i < 5 && cursor != null; i++) {
      ReadResult<Map<String, Object>> r = reader.read(2, cursor, ReaderTestContexts.newRun());
      allGood.addAll(recordsOf(r));
      allBad.addAll(reader.drainMalformed());
      cursor = nextCursorOrNull(r);
    }

    assertThat(allGood).hasSize(4);
    assertThat(allBad).hasSize(1);
    assertThat(allBad.get(0).line()).isEqualTo(4L);
  }

  @Test
  void larger_multi_chunk_skip_with_late_bad_row_is_correct(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("late.csv");
    // 6 good rows with a malformed row at event index 4 (file line 6): G,G,G,G,BAD,G,G
    Files.writeString(file,
        "a,b,c\n1,1,1\n2,2,2\n3,3,3\n4,4,4\nONLY_ONE\n6,6,6\n7,7,7\n");

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);

    List<Map<String, Object>> allGood = new ArrayList<>();
    List<MalformedRecord> allBad = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    for (int i = 0; i < 8 && cursor != null; i++) {
      ReadResult<Map<String, Object>> r = reader.read(2, cursor, ReaderTestContexts.newRun());
      allGood.addAll(recordsOf(r));
      allBad.addAll(reader.drainMalformed());
      cursor = nextCursorOrNull(r);
    }

    assertThat(allGood).hasSize(6);                 // every good row read exactly once
    assertThat(allBad).hasSize(1);                  // bad row counted exactly once across a skip of 4
    assertThat(allBad.get(0).line()).isEqualTo(6L); // the ONLY_ONE row
  }

  @Test
  void file_ending_exactly_on_chunk_boundary_signals_eof_in_one_read(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("exact.csv");
    Files.writeString(file, "a,b\n1,1\n2,2\n");   // exactly 2 good rows, chunkSize 2

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    ReadResult<Map<String, Object>> r = reader.read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());

    // The chunk fills AND the file ends exactly there → EndOfStream in this read, not HasMore
    // (HasMore would force a second, wasted read that re-opens and re-parses the whole file).
    assertThat(r).isInstanceOf(ReadResult.EndOfStream.class);
    assertThat(recordsOf(r)).hasSize(2);
  }

  @Test
  void a_file_whose_tail_is_malformed_still_ends_with_its_finished_position(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("tail.csv");
    Files.writeString(file, "a,b\n1,1\n2,2\nonly-one\n");   // two good rows, then a malformed one; chunk of 2

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    var first = (ReadResult.HasMore<Map<String, Object>>) reader.read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    var last = (ReadResult.EndOfStream<Map<String, Object>>) reader.read(2, first.nextCursor(), ReaderTestContexts.newRun());

    assertThat(last.records()).isEmpty();
    assertThat(reader.drainMalformed()).hasSize(1);
    assertThat(last.finalCursor().isEmpty()).as("the malformed tail was consumed, the position is saved").isFalse();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> recordsOf(ReadResult<Map<String, Object>> r) {
    if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) return hm.records();
    if (r instanceof ReadResult.EndOfStream<Map<String, Object>> eos) return eos.records();
    return List.of();
  }

  private static ConnectorCursor nextCursorOrNull(ReadResult<Map<String, Object>> r) {
    return (r instanceof ReadResult.HasMore<Map<String, Object>> hm) ? hm.nextCursor() : null;
  }
}
