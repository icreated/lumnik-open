package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunScope;
import io.lumnik.hub.connector.api.types.StreamEnd;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.writer.UnseenRowSweeper;
import io.lumnik.hub.connector.transport.LocalRoot;
import io.lumnik.hub.connector.transport.LocalRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1195: when a csv source is a state, the removal guard's forms, and what a run's read records. */
class CsvFullRefreshTest {

  @ParameterizedTest
  @CsvSource({"30%, 0.30", "50%, 0.50", "0%, 0.0", "100%, 1.0", "off, 1.0", "OFF, 1.0", "12.5%, 0.125"})
  void max_removed_reads_a_percentage_or_off(String declared, double fraction) {
    assertThat(CsvFullRefresh.maxRemoved(Map.of("refresh", Map.of("max_removed", declared)))).isEqualTo(fraction);
  }

  @Test
  void the_refusal_counts_the_rows_lost_not_the_versions_replaced() {
    // #1544: four rows lost and two changed — six old versions unmet, but the file holds six rows
    // where the table held ten: four would be lost.
    var refused = (StreamEnd.Refused) CsvFullRefresh.refused(new UnseenRowSweeper.Count(10, 6, 6), 0.30);
    assertThat(refused.reason()).startsWith("4 of 10 rows would be removed: the file holds 6 where the table held 10");
  }

  @Test
  void max_removed_defaults_to_thirty_percent() {
    assertThat(CsvFullRefresh.maxRemoved(Map.of())).isEqualTo(0.30);
    assertThat(CsvFullRefresh.maxRemoved(Map.of("refresh", Map.of()))).isEqualTo(0.30);
  }

  @ParameterizedTest
  @ValueSource(strings = {"150%", "-1%", "thirty", "", "on"})
  void max_removed_refuses_anything_else_naming_the_forms(String declared) {
    assertThatThrownBy(() -> CsvFullRefresh.maxRemoved(Map.of("refresh", Map.of("max_removed", declared))))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("0% to 100%").hasMessageContaining("off");
  }

  @Test
  void only_one_local_plain_file_holds_the_source_state() {
    assertThat(CsvFullRefresh.stateFile(transport("local", "/data/ref.csv")).isPresent()).isTrue();
    assertThat(CsvFullRefresh.stateFile(Map.of("path", "/data/ref.csv")).isPresent()).as("legacy path").isTrue();
    assertThat(CsvFullRefresh.stateFile(transport("local", "/data/*.csv")).isPresent()).isFalse();
    assertThat(CsvFullRefresh.stateFile(transport("local", "/data/ref-?.csv")).isPresent()).isFalse();
    assertThat(CsvFullRefresh.stateFile(transport("sftp", "/data/ref.csv")).isPresent()).isFalse();
    assertThat(CsvFullRefresh.stateFile(transport("s3", "bucket/ref.csv")).isPresent()).isFalse();
    assertThat(CsvFullRefresh.stateFile(Map.of()).isPresent()).isFalse();
  }

  @Test
  void a_run_that_starts_the_file_records_it_and_counts_what_it_read(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ref.csv");
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n");
    MapScope scope = new MapScope();

    reader(file).read(500, ConnectorCursor.empty(), ReaderTestContexts.of(scope));

    CsvFullRefresh.Pass pass = scope.pass();
    assertThat(pass.fromStart).isTrue();
    assertThat(pass.events).isEqualTo(3);
  }

  @Test
  void a_run_that_resumes_the_file_half_read_does_not_start_it(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ref.csv");
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n");
    // An earlier run stopped after two rows: its position is mid-file.
    var first = reader(file).read(2, ConnectorCursor.empty(), ReaderTestContexts.of(new MapScope()));
    ConnectorCursor midFile = ((ReadResult.HasMore<?>) first).nextCursor();

    MapScope scope = new MapScope();
    reader(file).read(500, midFile, ReaderTestContexts.of(scope));

    assertThat(scope.pass().fromStart).as("the first two rows were met by another run").isFalse();
  }

  @Test
  void a_file_that_changes_between_two_reads_of_one_run_taints_the_pass(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ref.csv");
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n");
    MapScope scope = new MapScope();
    var first = reader(file).read(2, ConnectorCursor.empty(), ReaderTestContexts.of(scope));
    ConnectorCursor midFile = ((ReadResult.HasMore<?>) first).nextCursor();

    Files.writeString(file, "id,name\n9,z\n1,a\n2,b\n3,c\n");   // rewritten while the run reads it
    reader(file).read(500, midFile, ReaderTestContexts.of(scope));

    assertThat(scope.pass().changed).as("the run met parts of two versions").isTrue();
  }

  @Test
  void a_file_rewritten_during_the_only_read_of_the_run_taints_the_pass(@TempDir Path dir) throws Exception {
    // #1214 review: the version was taken before the read and compared with itself, so a producer
    // writing while the single (or last) chunk was read went unseen and the sweep ran on it.
    Path file = dir.resolve("ref.csv");
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n");
    MapScope scope = new MapScope();
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(GlobReaders.ANYWHERE, file.toString(),
        CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true)), true, false) {
      @Override
      Walk fillChunk(List<Map<String, Object>> chunk, int chunkSize, int fileIndex, CsvCursor start) {
        Walk walk = super.fillChunk(chunk, chunkSize, fileIndex, start);
        try {   // the producer's write lands while this read is going
          Files.writeString(file, "id,name\n1,a\n");
          Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        return walk;
      }
    };

    reader.read(500, ConnectorCursor.empty(), ReaderTestContexts.of(scope));

    assertThat(scope.pass().changed).as("the run read a file that was being rewritten").isTrue();
  }

  private static CsvFileConnectorType.CsvGlobCursorReader reader(Path file) {
    return GlobReaders.of(file.toString(),
        CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true)), true);
  }

  private static Map<String, Object> transport(String kind, String path) {
    return Map.of("transport", Map.of("kind", kind, "path", path));
  }

  /**
   * #2001: the manifest writes the file relative to its tenant's folder, the reader reads the absolute path it names.
   * The run's record of its pass must be found by the end of the stream all the same — it was keyed by the path once,
   * and the two sides held it in two forms: nothing was ever removed.
   */
  @Test
  void a_relative_path_still_reaches_the_sweep(@TempDir Path drop) throws Exception {
    Files.createDirectories(drop.resolve("1"));
    Files.writeString(drop.resolve("1/ref.csv"), "id,name\n1,a\n2,b\n");
    CsvFileConnectorType type = new CsvFileConnectorType();
    type.localRoots = new LocalRoots(LocalRoot.at(drop));
    type.sweeper = Mockito.mock(UnseenRowSweeper.class);
    Mockito.when(type.sweeper.count(Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any()))
        .thenReturn(new UnseenRowSweeper.Count(2, 2, 0));
    MapScope scope = new MapScope();
    ConnectorContext ctx = new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "ref",
        CsvFileConnectorType.TYPE_ID, Map.of("name", "ref", "transport", Map.of("kind", "local", "path", "ref.csv")),
        new SyncMode.Incremental(), null, null, Clock.systemUTC(), null, scope);

    type.reader(ctx).read(500, ConnectorCursor.empty(), ctx);
    type.endOfStream(ctx);

    Mockito.verify(type.sweeper).count(Mockito.any(), Mockito.eq(1L), Mockito.any(), Mockito.any());
  }

  /** A scope that keeps what it built, so the test can read the run's record of its pass. */
  private static final class MapScope implements RunScope {
    private final Map<Object, AutoCloseable> held = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends AutoCloseable> T resource(Object key, Supplier<T> factory) {
      return (T) held.computeIfAbsent(key, k -> factory.get());
    }

    @Override
    public void invalidate(Object key) {
      held.remove(key);
    }

    /** The run's one pass: a full refresh has one file per run (#1195). */
    CsvFullRefresh.Pass pass() {
      return (CsvFullRefresh.Pass) held.get(new CsvFullRefresh.PassKey());
    }
  }
}
