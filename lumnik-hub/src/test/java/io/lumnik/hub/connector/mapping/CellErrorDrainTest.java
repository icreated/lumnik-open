package io.lumnik.hub.connector.mapping;

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

/**
 * The counter escapes the chunk (docs/superpowers/specs/2026-08-21-cell-errors-visible-design.md
 * §1): {@code MappingEngine.transformAndWrite} now returns a {@link MappingOutcome} carrying
 * {@code cellErrors} alongside the drops, and {@code MappingSpecItemWriter} — the writer every
 * MappingSpec-driven connector builds through — exposes it via {@link ItemWriter#drainCellErrors}
 * so {@code ChunkExecutor} can read it after the write, exactly the way it already drains
 * {@code ItemReader.drainMalformed()}.
 *
 * <p>The REAL cell-error count comes from the {@code prepare()} rehearsal pass, not from
 * {@code write()}: {@code prepare()} and {@code write()} deliberately transform the SAME row maps
 * (see {@link PrepareOutcome}'s javadoc — two hooks are content-nondeterministic across separate
 * passes, so the rows cannot be copied), which means by the time {@code write()}'s own cell hooks
 * run, the cells {@code prepare()} nulled are already {@code null} and every cell hook's
 * {@code if (v == null) return;} guard skips them. {@code MappingSpecItemWriter} sums
 * {@code prepare()}'s reported count with {@code write()}'s own — see the tests below for why
 * that sum is exactly right in both the prepare-ran and no-prepare cases.
 */
@QuarkusTest
class CellErrorDrainTest {

  @Inject MappingEngine engine;

  private static ConnectorContext ctx() {
    return new ConnectorContext(
        UUID.randomUUID(), null, null, 1L, "test", "test", Map.of(),
        new SyncMode.FullRefresh(), null, null, null, Clock.systemUTC());
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
  void prepare_then_write_still_counts_because_the_rehearsal_is_where_the_count_comes_from() {
    // ChunkExecutor's real sequence (executeChunk): writer.prepare(recordsRead, ctx) runs first,
    // OUTSIDE any transaction, on the SAME list of row maps that writer.write(...) is handed
    // afterwards — see ChunkExecutor.java:102-110 and :122-123/:141-142. This test reproduces that
    // exact composition.
    //
    // prepare() DELIBERATELY shares the row maps with write() (see PrepareOutcome's javadoc: two
    // hooks — to-uuid's generate:true, enrich-context's ${now} — are content-nondeterministic
    // across separate transform passes, and RagWriter.useCachedVectors would silently drop a
    // chunk whose prepare-pass and write-pass hashes disagree). So write()'s own transform sees
    // the cell prepare() already nulled and contributes 0 for it — the count this test proves
    // reaches drainCellErrors() is the REHEARSAL's count, reported back via PrepareOutcome and
    // summed in MappingSpecItemWriter.write.
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
        .as("the rehearsal pass (prepare()) is where the cell hooks actually run and count — "
            + "MappingSpecItemWriter must report that count, not rely on write()'s own pass "
            + "seeing a value to fail on")
        .isEqualTo(1);
  }

  @Test
  void write_alone_without_a_preceding_prepare_still_counts() {
    // The no-prepare path: a writer used outside ChunkExecutor's prepare phase (or a chunk with
    // no rows to prepare) never calls prepare(), so rehearsalCellErrors stays at its initial 0 —
    // the count must still come through entirely from write()'s own pass.
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
    // The risk the sum (rehearsalCellErrors + outcome.cellErrors()) introduces: if write()'s own
    // pass did NOT see the already-nulled cells (e.g. a future change broke the shared-mutation
    // contract), it would recompute its own cellErrors independently and the sum would DOUBLE
    // count. Two rows, each with one unparseable cell: the rehearsal must report 2, and write()'s
    // own contribution over the same (now-nulled) maps must be 0 — total 2, never 4.
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
        .as("each of the 2 unparseable cells must be counted exactly once — the rehearsal's "
            + "count, not the rehearsal's count PLUS a redundant recount by write()'s own pass "
            + "over the same already-nulled cells")
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
    // Regression guard for the deep-copy this fix's prior round introduced and then reverted:
    // to-uuid's generate:true stamps a NEW random value on every transform call. prepare() and
    // write() MUST transform the SAME row map, or the two passes would stamp two DIFFERENT UUIDs
    // into two different objects — RagWriter.useCachedVectors looks its prepared vector up by
    // the transformed row's hash, so a mismatch here means a real RAG chunk silently vanishes
    // (warn-and-skip, no error). See PrepareOutcome's javadoc for the full reasoning.
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
        .as("write() must transform the SAME row map prepare() did, or to-uuid's generate:true "
            + "stamps a second, different UUID and the two passes' row hashes disagree")
        .isEqualTo(afterPrepare);
  }

  @Test
  void a_bare_item_writer_overriding_nothing_returns_zero() {
    ItemWriter<String> writer = (items, c) -> new WriteOutcome.AllWritten(items.size());

    assertThat(writer.drainCellErrors()).isEqualTo(0);
  }
}
