package io.lumnik.hub.connector.event;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #904: the incremental resolution pass reads a source only when the run's {@link
 * ConnectorRunCompleted#tablesWritten} names its table, and that list is what {@link RunTableStats}
 * recorded. It is complete only if every writer that stamps a run's {@code _run_id} into a table
 * records that table — today one writer, {@code DynamicTableWriter}, whose {@code write} records it
 * whatever insert path ran ({@code DynamicTableWriterRunStatsIT}).
 *
 * <p>This inventory reads PRODUCTION SOURCE for every file that names the {@code _run_id} column, so a
 * second writer cannot appear unseen: a new file here that writes the column must record its table
 * into {@code RunTableStats} — or that run's rows in it are never resolved, and nothing says so. The
 * list also fails when a file it names stops naming the column, so a reason cannot outlive its code.
 */
class RunIdColumnInventoryTest {

  private static final Map<String, String> KNOWN = Map.of(
      "DynamicTableWriter", "the one writer: creates the column and stamps it; write() records the table",
      "MultiTargetWriter", "puts the run id into the system context DynamicTableWriter stamps; writes no table",
      "EntityResolutionPass", "reads it: the incremental selection");

  /** The column itself — not {@code active_run_id}, {@code last_rebuild_run_id} or any other {@code *_run_id}. */
  private static final Pattern RUN_ID_COLUMN = Pattern.compile("(?<![\\w])_run_id(?![\\w])");

  @Test
  void every_production_file_naming_the_run_id_column_is_accounted_for() throws IOException {
    Set<String> naming = new TreeSet<>();
    for (Path p : SourceTree.of("lumnik-hub", "io/lumnik/hub").javaFiles()) {
      if (RUN_ID_COLUMN.matcher(SourceTree.code(Files.readString(p))).find()) {
        naming.add(p.getFileName().toString().replace(".java", ""));
      }
    }
    assertThat(naming)
        .as("a file that writes _run_id into a table must record that table into RunTableStats (#904), "
            + "then be listed here with its reason")
        .isEqualTo(new TreeSet<>(KNOWN.keySet()));
  }
}
