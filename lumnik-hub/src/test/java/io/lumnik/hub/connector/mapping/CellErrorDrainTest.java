package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The counter escapes the chunk (docs/superpowers/specs/2026-08-21-cell-errors-visible-design.md
 * §1): {@code MappingEngine.transformAndWrite} now returns a {@link MappingOutcome} carrying
 * {@code cellErrors} alongside the drops, and {@code MappingSpecItemWriter} — the writer every
 * MappingSpec-driven connector builds through — exposes it via {@link ItemWriter#drainCellErrors}
 * so {@code ChunkExecutor} can read it after the write, exactly the way it already drains
 * {@code ItemReader.drainMalformed()}.
 *
 * <p>A chunk is transformed exactly once (#888, {@link PreparedChunk}): by {@code prepare()} when
 * {@code ChunkExecutor} ran it — {@code write()} then lands those rows — or by {@code write()}
 * itself when it did not. The count that reaches the run is that one transform's count, in both
 * cases; the tests below pin each.
 */
@QuarkusTest
class CellErrorDrainTest {

  @Inject MappingEngine engine;

  private static ConnectorContext ctx() {
    return new ConnectorContext(
        UUID.randomUUID(), null, null, 1L, "test", "test", Map.of(),
        new SyncMode.FullRefresh(), null, null, null, Clock.systemUTC(), null, new OwnedRunScope("test"));
  }

  @Test
  void unparseable_date_is_counted_and_the_row_is_still_written() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-date
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("dob", "not-a-date")));

    MappingOutcome outcome =
        engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), false, null, List.of());

    assertThat(outcome.cellErrors()).isEqualTo(1);
    assertThat(outcome.dropped()).isEmpty();
  }

  @Test
  void a_spec_with_no_cell_hooks_counts_zero() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-none
        version: 1
        target: x
        """);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("a", "1")));

    MappingOutcome outcome =
        engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), false, null, List.of());

    assertThat(outcome.cellErrors()).isEqualTo(0);
  }

  @Test
  void drain_returns_the_last_writes_count_then_zero_on_a_second_call() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-drain
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("dob", "not-a-date")));

    WriteOutcome outcome = writer.write(input, ctx());

    assertThat(outcome).isInstanceOf(WriteOutcome.AllWritten.class);
    assertThat(writer.drainCellErrors()).isEqualTo(1);
    assertThat(writer.drainCellErrors()).isEqualTo(0);
  }

  @Test
  void prepare_then_write_counts_the_transform_prepare_ran() {
    // ChunkExecutor's real sequence (executeChunk): writer.prepare(recordsRead, ctx) runs first,
    // OUTSIDE any transaction, and writer.write(...) is handed the SAME list afterwards. This test
    // reproduces that exact composition: prepare() is where the chunk's one transform runs, so its
    // count is the one write() must report.
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-prepare-then-write
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("dob", "not-a-date")));

    writer.prepare(input, ctx());
    WriteOutcome outcome = writer.write(input, ctx());

    assertThat(outcome).isInstanceOf(WriteOutcome.AllWritten.class);
    assertThat(writer.drainCellErrors())
        .as("prepare() is where the cell hooks run and count — write() lands its rows and must "
            + "report that count, not lose it because nothing transforms the chunk again")
        .isEqualTo(1);
  }

  @Test
  void write_alone_without_a_preceding_prepare_still_counts() {
    // The no-prepare path: a writer used outside ChunkExecutor's prepare phase (or a chunk with
    // no rows to prepare) never calls prepare(), so write() runs the chunk's one transform and the
    // count comes entirely from it.
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-write-alone
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("dob", "not-a-date")));

    WriteOutcome outcome = writer.write(input, ctx());

    assertThat(outcome).isInstanceOf(WriteOutcome.AllWritten.class);
    assertThat(writer.drainCellErrors()).isEqualTo(1);
  }

  @Test
  void prepare_then_write_counts_each_failure_exactly_once_not_twice() {
    // A second transform in write() would count every failure again (#888 counted a split
    // shortfall twice). Two rows, each with one unparseable cell: 2, never 4.
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-exactly-once
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("dob", "not-a-date")),
        new HashMap<>(Map.of("dob", "also-not-a-date")));

    writer.prepare(input, ctx());
    WriteOutcome outcome = writer.write(input, ctx());

    assertThat(outcome).isInstanceOf(WriteOutcome.AllWritten.class);
    assertThat(writer.drainCellErrors())
        .as("each of the 2 unparseable cells must be counted exactly once — prepare()'s count, "
            + "never that count PLUS a recount by a second transform in write()")
        .isEqualTo(2);
  }

  @Test
  void two_writes_before_a_drain_accumulate_rather_than_overwrite() {
    // A single ItemWriter instance lives for one chunk (MappingEngine.itemWriter's javadoc), but
    // ChunkExecutor calls write() exactly once per chunk before draining — this test still pins
    // the field-level arithmetic in isolation: MappingSpecItemWriter.write must ADD to cellErrors,
    // not assign, or a second write in the same chunk's lifetime would lose the first count.
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-accumulate
        version: 1
        target: x
        cell:
          - { parse-date: { column: dob, formats: ['yyyy-MM-dd'] } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    List<Map<String, Object>> first = List.of(new HashMap<>(Map.of("dob", "not-a-date")));
    List<Map<String, Object>> second = List.of(new HashMap<>(Map.of("dob", "still-not-a-date")));

    writer.write(first, ctx());
    writer.write(second, ctx());

    assertThat(writer.drainCellErrors())
        .as("cellErrors must accumulate across writes within the same drain window, exactly like "
            + "MappingState.Counters.cellErrors does within a chunk")
        .isEqualTo(2);
  }

  @Test
  void prepare_then_write_share_the_same_generated_uuid_not_two_different_ones() {
    // to-uuid's generate:true stamps a NEW random value on every transform call. The row write()
    // lands must carry the value prepare() stamped — RagWriter.useCachedVectors looks its prepared
    // vector up by the transformed row's hash, so a second stamp means a real RAG chunk silently
    // vanishes (warn-and-skip, no error). See PreparedChunk for why nothing transforms twice.
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-shared-mutation-uuid
        version: 1
        target: x
        cell:
          - { to-uuid: { column: ref, generate: true } }
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    Map<String, Object> row = new HashMap<>();
    row.put("ref", null);
    List<Map<String, Object>> input = List.of(row);

    writer.prepare(input, ctx());
    Object afterPrepare = row.get("ref");
    writer.write(input, ctx());
    Object afterWrite = row.get("ref");

    assertThat(afterPrepare).isNotNull();
    assertThat(afterWrite)
        .as("write() must land the row prepare() transformed, or to-uuid's generate:true "
            + "stamps a second, different UUID and the two phases' row hashes disagree")
        .isEqualTo(afterPrepare);
  }

  @Test
  void write_refuses_records_other_than_the_ones_it_prepared() {
    // write() lands the rows prepare() transformed (#888); handed any other list it would land rows
    // that are not the ones it was given. ChunkExecutor always hands write() the list it handed
    // prepare(), so this is a contract, refused loudly rather than written wrongly.
    MappingSpec spec = MappingLoader.load("""
        id: prepared-for-another-list
        version: 1
        target: x
        """);
    ItemWriter<Map<String, Object>> writer = engine.itemWriter(spec);
    writer.prepare(List.of(new HashMap<>(Map.of("a", "1"))), ctx());

    assertThatThrownBy(() -> writer.write(List.of(new HashMap<>(Map.of("a", "2"))), ctx()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_bare_item_writer_overriding_nothing_returns_zero() {
    ItemWriter<String> writer = (items, c) -> new WriteOutcome.AllWritten(items.size());

    assertThat(writer.drainCellErrors()).isEqualTo(0);
  }
}
