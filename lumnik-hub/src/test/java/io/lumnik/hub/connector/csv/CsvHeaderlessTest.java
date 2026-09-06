package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CsvHeaderlessTest {

  /**
   * Headerless, with {@code columns} declared in the config — the shape every test here needs.
   * Written once because {@code Config} is a positional record whose {@code hasHeader} and
   * {@code bomStrip} are ADJACENT booleans: spelled out at each call site, only a comment keeps
   * them apart, and swapping them compiles. Same reason {@code CsvFileReaderCheckpointTest} has
   * its own factory.
   */
  private static CsvFileReader.Config headerless(List<String> columns) {
    return new CsvFileReader.Config(StandardCharsets.UTF_8, ',',
        /*hasHeader*/ false, /*bomStrip*/ true, List.of("", "NULL"), columns);
  }

  @Test
  void headerless_uses_configured_columns_and_keeps_first_row_as_data(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("noheader.csv");
    Files.writeString(file, "1,alice@x.com\n2,bob@x.com\n", StandardCharsets.UTF_8);

    var cfg = headerless(List.of("id", "email"));

    List<Map<String, Object>> rows = new ArrayList<>();
    new CsvFileReader().readAll(file, cfg, rows::add);

    assertThat(rows).hasSize(2);                                  // first row is DATA, not header
    assertThat(rows.get(0)).containsEntry("id", "1").containsEntry("email", "alice@x.com");
    assertThat(rows.get(1)).containsEntry("id", "2").containsEntry("email", "bob@x.com");
  }

  /**
   * The column-count rule is the reader's, not the header line's: a headerless file declares its
   * columns in the config instead, and a row carrying more values than were declared is
   * quarantined exactly as it would be under a header line.
   *
   * <p>Worth its own case because this is the branch a CHECKPOINT RESUME parses through — the
   * resumed window rebuilds the config headerless, with the cursor's captured column names — so
   * a rule that held only on the header branch would lapse the moment a large file spanned a
   * chunk boundary.
   */
  @Test
  void headerless_row_with_more_values_than_declared_columns_is_quarantined(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("noheader-wide.csv");
    // A well-shaped row on either side of the offending one: quarantining isolates a row, it
    // neither discards what came before nor ends the file.
    Files.writeString(file, "1,alice@x.com\n2,bob@x.com,SURPLUS\n3,carol@x.com\n",
        StandardCharsets.UTF_8);

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, headerless(List.of("id", "email")), good::add, bad::add);

    assertThat(good).as("the rows before AND after the offending one both land").hasSize(2);
    assertThat(bad).as("the wide row is quarantined, not trimmed to fit").hasSize(1);
    assertThat(bad.get(0).rawValues())
        .as("the surplus value is preserved in the payload — losing it silently was the defect")
        .containsExactly("2", "bob@x.com", "SURPLUS");
    assertThat(bad.get(0).message()).contains("3 values but only 2 columns are declared");
  }

  @Test
  void headerless_without_columns_fails_loudly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("noheader.csv");
    Files.writeString(file, "1,alice@x.com\n", StandardCharsets.UTF_8);

    var cfg = headerless(/*columns*/ null);

    assertThatThrownBy(() -> new CsvFileReader().readAll(file, cfg, r -> {}))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("has-header");
  }
}
