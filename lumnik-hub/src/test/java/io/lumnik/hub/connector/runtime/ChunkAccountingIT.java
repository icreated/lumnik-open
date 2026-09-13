package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conservation invariant of one chunk — {@code records_in = records_out + records_skipped} —
 * held against every {@link WriteOutcome} a writer can return.
 *
 * <p>{@code ChunkExecutor.executeChunk} takes the {@link ConnectorType} as an argument, so the whole
 * outcome space is reachable with a reader that yields a fixed batch and a writer that returns the
 * outcome under test. That is the point of this file: {@code ChunkExecutorIT} drives the happy path
 * through {@code hello-world}, which can only ever produce {@code AllWritten}.
 */
@QuarkusTest
class ChunkAccountingIT {

    private static final int BATCH = 3;

    @Inject ChunkExecutor exec;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject ConnectorDeadLetterRepository deadLetters;

    /**
     * The two outcomes a writer can return that still account for every row it was handed.
     * {@code AllWritten(n)} and {@code PartialWrite(w, skipped)} must together cover the batch —
     * an {@code AllWritten(0)} for a non-empty batch is not a case to test but a writer lying about
     * its own count, and it breaks conservation by construction. {@code AllFailed} accounts for
     * nothing on purpose and is covered by the refusal test below.
     */
    static Stream<Arguments> outcomes() {
        return Stream.of(
            Arguments.of("all written · mid-stream", false, allWritten(), BATCH, 0),
            Arguments.of("all written · final chunk", true, allWritten(), BATCH, 0),
            Arguments.of("partial, one row skipped by policy · mid-stream", false, partial(), BATCH - 1, 1),
            Arguments.of("partial, one row skipped by policy · final chunk", true, partial(), BATCH - 1, 1)
        );
    }

    private static WriteOutcome allWritten() { return new WriteOutcome.AllWritten(BATCH); }

    private static WriteOutcome partial() {
        return new WriteOutcome.PartialWrite(BATCH - 1,
            List.of(new WriteOutcome.SkippedRecord("r3", new SkipReason.PolicyExcluded("test"))));
    }

    @ParameterizedTest(name = "{0}: in = out + skipped")
    @MethodSource("outcomes")
    @TestTransaction
    void conservationHolds(String label, boolean finalChunk, WriteOutcome outcome,
                           int expectedOut, int expectedSkipped) {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            ChunkResult res = exec.executeChunk(run, typeReturning(outcome, finalChunk), endpointOf(run),
                                                ConnectorCursor.empty(), null);
            ConnectorRun after = runs.findById(run.getId());

            assertThat(after.getRecordsIn()).as("%s — records read", label).isEqualTo(BATCH);
            assertThat(after.getRecordsOut()).as("%s — records written", label).isEqualTo(expectedOut);
            assertThat(after.getRecordsSkipped()).as("%s — records skipped", label).isEqualTo(expectedSkipped);
            assertThat(after.getRecordsIn())
                .as("%s — CONSERVATION: every row read is either written or skipped", label)
                .isEqualTo(after.getRecordsOut() + after.getRecordsSkipped());
            assertThat(res).isNotNull();
        });
    }

    /**
     * A batch nothing wrote must not move the cursor. {@code executeChunk}'s own javadoc states the
     * guarantee: "the write happens first and the cursor is persisted only after it returns, so a
     * cursor can never advance past rows that were never written."
     */
    @ParameterizedTest(name = "a wholesale refusal ({0}) fails the chunk and moves nothing")
    @MethodSource("refusals")
    @TestTransaction
    void aRefusedBatchFailsTheChunkAndMovesNothing(String label, boolean finalChunk, WriteOutcome refused) {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            ChunkResult res = exec.executeChunk(run, typeReturning(refused, finalChunk), endpointOf(run),
                                                ConnectorCursor.empty(), null);

            assertThat(res).as("%s — the chunk failed", label).isInstanceOf(ChunkResult.Failure.class);
            ConnectorRun after = runs.findById(run.getId());
            assertThat(after.getRecordsIn()).as("%s — nothing counted in", label).isZero();
            assertThat(after.getRecordsOut()).as("%s — nothing counted out", label).isZero();
            assertThat(after.getRecordsSkipped()).as("%s — nothing counted skipped", label).isZero();
            assertThat(cursors.findByEndpoint(run.getEndpointId()))
                .as("%s — THE CURSOR MUST NOT ADVANCE past rows nothing wrote", label)
                .isNull();
        });
    }

    /**
     * Both read shapes, because the guard exists twice. A writer can refuse a mid-stream chunk
     * ({@code HasMore}) or the last one ({@code EndOfStream}), and each branch of the switch carries
     * its own copy of the refusal check — so each needs its own case, or one of the two is written
     * and never proven. AllFailed's cause is nullable: a writer may refuse with no throwable to hand
     * over, and the failure path must not dereference it.
     */
    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of("mid-stream, with a cause", false, refusedWithCause()),
            Arguments.of("mid-stream, no cause", false, refusedWithoutCause()),
            Arguments.of("final chunk, with a cause", true, refusedWithCause()),
            Arguments.of("final chunk, no cause", true, refusedWithoutCause())
        );
    }

    private static WriteOutcome refusedWithCause() {
        return new WriteOutcome.AllFailed("boom", new IllegalStateException("boom"));
    }

    private static WriteOutcome refusedWithoutCause() {
        return new WriteOutcome.AllFailed("refused, no throwable", null);
    }

    /**
     * A row the SOURCE could not parse counts on BOTH sides of the identity, and that is not double
     * counting: it WAS read (so it belongs to {@code records_in}) and it was NOT written (so it
     * belongs to {@code records_skipped}). {@code ChunkExecutor} says so in a comment — "otherwise
     * conservation breaks for a dead-lettered-at-read row (in=0, dlq=1)". This asserts it, and
     * asserts each malformed row actually reaches the dead-letter queue rather than only moving a
     * counter. The {@code 0 good} rows case matters most: a window where EVERY row is malformed.
     */
    @ParameterizedTest(name = "{0}: {1} good + {2} malformed conserve, and the malformed reach the DLQ")
    @CsvSource({"mid-stream, 3, 2", "mid-stream, 0, 2", "final chunk, 3, 2", "final chunk, 0, 2"})
    @TestTransaction
    void malformedRowsCountOnBothSidesAndAreDeadLettered(String shape, int good, int malformed) {
        boolean finalChunk = shape.startsWith("final");
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            long dlqBefore = deadLetters.count();

            exec.executeChunk(run, typeWithMalformed(good, malformed, finalChunk), endpointOf(run),
                              ConnectorCursor.empty(), null);
            ConnectorRun after = runs.findById(run.getId());

            assertThat(after.getRecordsIn()).as("read = good + malformed").isEqualTo(good + malformed);
            assertThat(after.getRecordsOut()).as("only the good rows are written").isEqualTo(good);
            assertThat(after.getRecordsSkipped()).as("the malformed rows are skipped").isEqualTo(malformed);
            assertThat(after.getRecordsIn())
                .as("CONSERVATION with malformed rows in the batch")
                .isEqualTo(after.getRecordsOut() + after.getRecordsSkipped());
            assertThat(deadLetters.count() - dlqBefore)
                .as("every malformed row is captured, not merely counted").isEqualTo(malformed);
        });
    }

    /**
     * Cell errors are a THIRD tally, outside the row identity: a cell hook that cannot parse a value
     * nulls the cell and lets the row through, so the row is still written. {@code ChunkExecutor}
     * drains them exactly once per chunk, with a comment warning that a second call "would return 0
     * and silently LOSE the count rather than double it" — a sentence describing precisely how to
     * break a counter, with nothing pinning it. This pins it: the tally lands, and conservation over
     * ROWS is untouched by it.
     */
    @ParameterizedTest(name = "{0}: {1} cell errors folded in once, rows unaffected")
    @CsvSource({"mid-stream, 0", "mid-stream, 4", "final chunk, 4"})
    @TestTransaction
    void cellErrorsAreDrainedOnceAndDoNotDisturbRowConservation(String shape, long cellErrors) {
        boolean finalChunk = shape.startsWith("final");
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            exec.executeChunk(run, typeWithCellErrors(cellErrors, finalChunk), endpointOf(run),
                              ConnectorCursor.empty(), null);
            ConnectorRun after = runs.findById(run.getId());

            assertThat(after.getCellErrors()).as("the cell tally is folded in exactly once")
                .isEqualTo(cellErrors);
            assertThat(after.getRecordsIn()).as("a nulled cell does not drop its row").isEqualTo(BATCH);
            assertThat(after.getRecordsIn())
                .as("CONSERVATION over rows is independent of the cell tally")
                .isEqualTo(after.getRecordsOut() + after.getRecordsSkipped());
        });
    }

    /**
     * A window that yields no VALID row can still have isolated malformed ones — a CSV chunk where
     * every line is unparsable is the obvious shape. {@code EmptyButOpen} has no producer in the
     * repository today, so this is a trap rather than a live loss: the branch returns Success without
     * draining the reader, and the day someone writes a reader that reports "nothing usable, stream
     * still open", those rows leave no counter and no dead letter. Asserting it now costs one case
     * and closes the branch before it has a caller.
     */
    @ParameterizedTest(name = "an empty-but-open window still accounts for its {0} malformed rows")
    @CsvSource({"1", "3"})
    @TestTransaction
    void anEmptyButOpenWindowStillDeadLettersItsMalformedRows(int malformed) {
        TenantContext.runAsSystem(() -> {
            ConnectorRun run = fixture();
            long dlqBefore = deadLetters.count();

            exec.executeChunk(run, typeEmptyButOpenWithMalformed(malformed), endpointOf(run),
                              ConnectorCursor.empty(), null);
            ConnectorRun after = runs.findById(run.getId());

            assertThat(after.getRecordsIn()).as("the malformed rows WERE read").isEqualTo(malformed);
            assertThat(after.getRecordsOut()).as("nothing was written").isZero();
            assertThat(after.getRecordsSkipped()).as("and none of them were written")
                .isEqualTo(malformed);
            assertThat(after.getRecordsIn())
                .as("CONSERVATION holds even when the window yielded no usable row")
                .isEqualTo(after.getRecordsOut() + after.getRecordsSkipped());
            assertThat(deadLetters.count() - dlqBefore)
                .as("they reach the dead-letter queue, not the void").isEqualTo(malformed);
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ConnectorRun fixture() {
        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("chunk-accounting");
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", BATCH));
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint e = new ConnectorEndpoint();
        e.setTenantId(1L);
        e.setConnectorId(c.getId());
        e.setName("default");
        e.setMethod("GET");
        e.setPath("/default");
        e.setEnabled(true);
        e.setConfig(Map.of("count", BATCH));
        endpoints.persist(e);
        endpoints.flush();

        ConnectorRun r = new ConnectorRun();
        r.setTenantId(1L);
        r.setConnectorId(c.getId());
        r.setEndpointId(e.getId());
        r.setStatus("Running");
        r.setSyncMode("Incremental");
        r.setStartedAt(Instant.now());
        r.setTriggeredBy("MANUAL");
        runs.persist(r);
        runs.flush();
        return r;
    }

    private ConnectorEndpoint endpointOf(ConnectorRun run) {
        return endpoints.findById(run.getEndpointId());
    }

    /** A reader yielding {@code good} rows plus {@code malformed} unparsable ones, drained once. */
    private static ConnectorType typeWithMalformed(int good, int malformed, boolean finalChunk) {
        List<String> rows = IntStream.range(0, good).mapToObj(i -> "r" + i).toList();
        return type(ctx -> new ItemReader<String>() {
            private boolean drained = false;
            @Override public ReadResult<String> read(int chunkSize, ConnectorCursor cur, ConnectorContext c) {
                return finalChunk ? new ReadResult.EndOfStream<>(rows)
                                  : new ReadResult.HasMore<>(rows, ConnectorCursor.empty());
            }
            @Override public List<MalformedRecord> drainMalformed() {
                if (drained) return List.of();     // draining empties, like every real reader
                drained = true;
                return IntStream.range(0, malformed)
                    .mapToObj(i -> new MalformedRecord("f.csv", i + 1L, List.of("bad"), "unparsable"))
                    .toList();
            }
        }, (items, c) -> new WriteOutcome.AllWritten(items.size()));
    }

    /** A reader reporting "nothing usable this pass, stream still open" with malformed rows buffered. */
    private static ConnectorType typeEmptyButOpenWithMalformed(int malformed) {
        return type(ctx -> new ItemReader<String>() {
            private boolean drained = false;
            @Override public ReadResult<String> read(int chunkSize, ConnectorCursor cur, ConnectorContext c) {
                return new ReadResult.EmptyButOpen<>(ConnectorCursor.empty());
            }
            @Override public List<MalformedRecord> drainMalformed() {
                if (drained) return List.of();
                drained = true;
                return IntStream.range(0, malformed)
                    .mapToObj(i -> new MalformedRecord("f.csv", i + 1L, List.of("bad"), "unparsable"))
                    .toList();
            }
        }, (items, c) -> new WriteOutcome.AllWritten(items.size()));
    }

    /** A writer reporting {@code cellErrors} nulled cells, drained once. */
    private static ConnectorType typeWithCellErrors(long cellErrors, boolean finalChunk) {
        return type(ctx -> (chunkSize, cur, c) -> finalChunk
                        ? new ReadResult.EndOfStream<>(List.of("r1", "r2", "r3"))
                        : new ReadResult.HasMore<>(List.of("r1", "r2", "r3"), ConnectorCursor.empty()),
                    new ItemWriter<String>() {
                        private boolean drained = false;
                        @Override public WriteOutcome write(List<String> items, ConnectorContext c) {
                            return new WriteOutcome.AllWritten(items.size());
                        }
                        @Override public long drainCellErrors() {
                            if (drained) return 0;   // draining empties, like the mapping writer
                            drained = true;
                            return cellErrors;
                        }
                    });
    }

    /** The ConnectorType boilerplate every fake here shares. */
    private static ConnectorType type(Function<ConnectorContext, ItemReader<String>> reader,
                                      ItemWriter<String> writer) {
        return new ConnectorType() {
            @Override public String id() { return "test-accounting"; }
            @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
            @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
            @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }
            @Override public ItemReader<String> reader(ConnectorContext ctx) { return reader.apply(ctx); }
            @Override public ItemWriter<String> writer(ConnectorContext ctx) { return writer; }
        };
    }

    /**
     * A connector type whose reader yields BATCH rows — as the final chunk of a stream
     * ({@code EndOfStream}) or as one of many ({@code HasMore}) — and whose writer returns
     * {@code outcome}.
     */
    private static ConnectorType typeReturning(WriteOutcome outcome, boolean finalChunk) {
        return new ConnectorType() {
            @Override public String id() { return "test-accounting"; }
            @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
            @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
            @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

            @Override public ItemReader<String> reader(ConnectorContext ctx) {
                return (chunkSize, cursor, c) -> finalChunk
                    ? new ReadResult.EndOfStream<>(List.of("r1", "r2", "r3"))
                    : new ReadResult.HasMore<>(List.of("r1", "r2", "r3"), ConnectorCursor.empty());
            }

            @Override public ItemWriter<String> writer(ConnectorContext ctx) {
                return (items, c) -> outcome;
            }
        };
    }
}
