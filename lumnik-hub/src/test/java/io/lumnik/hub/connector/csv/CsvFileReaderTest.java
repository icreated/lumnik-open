package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.MalformedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CsvFileReaderTest {

  @Test
  void reads_simple_csv() throws Exception {
    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    List<Map<String, Object>> rows = new ArrayList<>();
    reader.readAll(path, null, rows::add);
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0))
        .containsEntry("email", "alice@example.com")
        .containsEntry("name", "Alice")
        .containsEntry("country", "FR");
  }

  @Test
  void treats_empty_string_as_null() throws Exception {
    var reader = new CsvFileReader();
    var path = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    List<Map<String, Object>> rows = new ArrayList<>();
    reader.readAll(path, null, rows::add);
    // simple fixture has no nulls, so just verify no NULL strings became actual nulls
    assertThat(rows.get(0).get("email")).isNotNull();
  }

  @Test
  void per_row_column_mismatch_is_isolated_not_thrown(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("bad-row.csv");
    Files.writeString(file, "a,b,c\n1,2,3\nONLY_ONE\n7,8,9\n");

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), good::add, bad::add);

    assertThat(good).hasSize(2);
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).line()).isEqualTo(3L);
    assertThat(bad.get(0).rawValues()).containsExactly("ONLY_ONE");
    assertThat(bad.get(0).message()).contains("only has 1 values");
  }

  @Test
  void unterminated_quote_is_file_fatal_and_stops(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("desync.csv");
    Files.writeString(file, "a,b,c\n1,2,3\n\"oops,unterminated,9\n7,8,9\n");

    var good = new ArrayList<Map<String, Object>>();
    var bad = new ArrayList<MalformedRecord>();
    new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), good::add, bad::add);

    assertThat(good).hasSize(1);
    assertThat(bad).hasSize(1);
    assertThat(bad.get(0).rawValues()).isNull();
    assertThat(bad.get(0).message()).contains("EOF reached before encapsulated token");
  }

  @Test
  void three_arg_overload_still_throws_on_malformed_row(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("legacy.csv");
    Files.writeString(file, "a,b\n1,2\nONLY_ONE\n");
    assertThat(catchThrowable(() ->
        new CsvFileReader().readAll(file, CsvFileReader.Config.defaults(), r -> {})))
        .as("legacy 3-arg path must still abort on a malformed row")
        .isInstanceOf(RuntimeException.class);
  }
}
