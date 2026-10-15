package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvGlobAndCursorTest {

  @Test
  void glob_enumerates_files_in_lexical_order() throws Exception {
    Path dir = Path.of(getClass().getResource("/csv/glob-test").toURI());
    List<Path> files = CsvGlobResolver.resolve(dir.toString() + "/*.csv");
    assertThat(files).hasSize(2);
    assertThat(files.get(0).getFileName().toString()).isEqualTo("glob-a.csv");
    assertThat(files.get(1).getFileName().toString()).isEqualTo("glob-b.csv");
  }

  @Test
  void glob_with_single_file_returns_one() throws Exception {
    Path file = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    List<Path> files = CsvGlobResolver.resolve(file.toString());
    assertThat(files).hasSize(1);
  }

  /**
   * A9 (tribunal 2026-07-25) — the STATED contract, pinned as characterization: the local
   * glob cursor resumes at the current file's index in the LEXICALLY sorted list, so a
   * drop directory must use monotonically increasing names (e.g. date-prefixed). A file
   * arriving later with a lexically earlier name is treated as already ingested and never
   * read. Changing this means adopting per-file completion tracking (the FileSource
   * cursor model) — a deliberate redesign, not a bugfix; see docs/connectors/csv.md.
   */
  @Test
  void contract_lateArrivingLexicallyEarlierFile_isTreatedAsAlreadyIngested(@TempDir Path dir)
      throws Exception {
    Files.writeString(dir.resolve("b.csv"), "id,name\n1,b1\n2,b2\n3,b3\n");
    var overrides = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true));
    String glob = dir + "/*.csv";

    // chunk of 2: b.csv is mid-file, the cursor persists at (b.csv, 2 events)
    var r1 = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides)
        .read(2, ConnectorCursor.empty(), ReaderTestContexts.newRun());
    assertThat(r1).isInstanceOf(ReadResult.HasMore.class);
    var c1 = ((ReadResult.HasMore<?>) r1).nextCursor();

    // a.csv arrives later but sorts BEFORE the cursor file
    Files.writeString(dir.resolve("a.csv"), "id,name\n9,never-read\n");

    var r2 = new CsvFileConnectorType.CsvGlobCursorReader(glob, overrides).read(500, c1, ReaderTestContexts.newRun());
    assertThat(r2).isInstanceOf(ReadResult.EndOfStream.class);
    List<?> rows = ((ReadResult.EndOfStream<?>) r2).records();
    List<Object> names = rows.stream().map(r -> (Object) ((Map<?, ?>) r).get("name")).toList();
    assertThat(names)
        .as("resume finishes b.csv; the lexically earlier late arrival is skipped by contract")
        .containsExactly("b3");
  }

  @Test
  void cursor_format_round_trip() {
    var cursor = new CsvCursor("/imports/customers.csv", 42);
    String json = cursor.toJson();
    var restored = CsvCursor.fromJson(json);
    assertThat(restored.file()).isEqualTo("/imports/customers.csv");
    assertThat(restored.rowsProcessed()).isEqualTo(42);
  }
}
