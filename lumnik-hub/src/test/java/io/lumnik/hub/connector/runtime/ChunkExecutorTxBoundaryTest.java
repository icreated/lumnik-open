package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.node.TextNode;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.StreamEnd;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the transactional boundary of {@link ChunkExecutor#executeChunk}.
 *
 * <p>The chunk used to run entirely inside ONE {@code @Transactional} method, so the pooled
 * {@code default} connection (and its {@code SET LOCAL app.current_tenant}) was held across
 * {@code reader.read(...)} — a round-trip to the customer's legacy source system. Under real
 * ingestion that exhausted the 20-slot Agroal pool.
 *
 * <p>Two properties are pinned here, and they pull in opposite directions:
 * <ol>
 *   <li><b>Latency</b> — the source round-trip must happen with NO transaction active.</li>
 *   <li><b>Atomicity</b> — the write, the cursor and the run counters must still commit
 *       together, or a cursor could advance past rows that were never written (silent data
 *       loss). So a transaction MUST be active during the write, and a writer failure must
 *       leave the cursor untouched.</li>
 * </ol>
 *
 * <p>The recorders are <b>fail-closed</b>: each is initialised to {@code true} ("a transaction
 * was active"), so a callback that never runs cannot make the latency assertion pass vacuously.
 *
 * <p>{@code executeChunk} is invoked with NO ambient transaction — the production shape
 * ({@code ConnectorEngine.executeChunkManaged}).
 */
@QuarkusTest
class ChunkExecutorTxBoundaryTest {

    @Inject ChunkExecutor exec;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject TransactionManager tm;

    private UUID endpointId;
    private UUID runId;
    private ConnectorRun run;
    private ConnectorEndpoint endpoint;

    /** Cursor the fake reader hands back — the value the chunk is expected to persist. */
    private static final ConnectorCursor NEXT =
        new ConnectorCursor(TextNode.valueOf("cursor-after-chunk"), Instant.EPOCH);

    /**
     * A connector type whose reader and writer are recording doubles. It is passed straight to
     * {@code executeChunk}, so nothing needs to be registered in the CDI type registry.
     */
    static final class RecordingType implements ConnectorType {
        final AtomicBoolean txDuringRead    = new AtomicBoolean(true);   // fail-closed
        final AtomicBoolean txDuringPrepare = new AtomicBoolean(true);   // fail-closed
        final AtomicBoolean txDuringWrite   = new AtomicBoolean(true);   // fail-closed
        final AtomicBoolean prepareCalled   = new AtomicBoolean(false);
        final AtomicBoolean writeCalled     = new AtomicBoolean(false);
        /** True only if prepare() ran and returned before write() started. */
        final AtomicBoolean prepareBeforeWrite = new AtomicBoolean(false);
        final boolean writerFails;
        final boolean prepareFails;

        RecordingType(boolean writerFails) { this(writerFails, false); }

        RecordingType(boolean writerFails, boolean prepareFails) {
            this.writerFails = writerFails;
            this.prepareFails = prepareFails;
        }

        @Override public String id() { return "tx-boundary-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            return (chunkSize, cursor, c) -> {
                txDuringRead.set(QuarkusTransaction.isActive());
                return new ReadResult.HasMore<>(List.of(Map.of("k", "v")), NEXT);
            };
        }

        @Override public ItemWriter<Map<String, Object>> writer(ConnectorContext ctx) {
            return new ItemWriter<>() {
                @Override public void prepare(List<Map<String, Object>> records, ConnectorContext c) {
                    txDuringPrepare.set(QuarkusTransaction.isActive());
                    prepareCalled.set(true);
                    if (prepareFails) throw new IllegalStateException("prepare blew up");
                }

                @Override public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext c) {
                    txDuringWrite.set(QuarkusTransaction.isActive());
                    writeCalled.set(true);
                    prepareBeforeWrite.set(prepareCalled.get());
                    if (writerFails) throw new IllegalStateException("writer blew up");
                    return new WriteOutcome.AllWritten(items.size());
                }
            };
        }
    }

    /**
     * A type that refuses to BUILD its reader. This is the shape of a configuration refusal, not of
     * a crash: {@code jdbc-generic} resolves its credential from inside {@code reader(ctx)}, so
     * "no secret named acme-token" arrives exactly here, before anything has been read.
     */
    static final class UnbuildableType implements ConnectorType {
        static final String REFUSAL = "no secret named acme-token";

        @Override public String id() { return "unbuildable-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            throw new IllegalStateException(REFUSAL);
        }
    }

    /**
     * #1214 review: a type whose stream ends with a position, and whose {@code endOfStream} records
     * the position stored when it is called — its side effects (a csv full refresh deletes rows,
     * committed on the admin datasource) must come after the step that can still fail the chunk.
     */
    final class EndingType implements ConnectorType {
        final AtomicReference<String> storedWhenEnding = new AtomicReference<>("never called");
        final boolean refuses;

        EndingType(boolean refuses) { this.refuses = refuses; }

        @Override public String id() { return "ending-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            return (chunkSize, cursor, c) -> new ReadResult.EndOfStream<>(List.of(Map.of("k", "v")), NEXT);
        }

        @Override public StreamEnd endOfStream(ConnectorContext ctx) {
            ConnectorCursorEntity stored = cursors.findByEndpoint(endpointId);
            storedWhenEnding.set(stored == null ? null : stored.getCursorValue().asText());
            return refuses ? new StreamEnd.Refused("refused at the end") : new StreamEnd.Removed(0);
        }
    }

    @Test
    void the_final_position_is_stored_before_the_type_ends_the_stream() {
        EndingType type = new EndingType(false);

        TenantContext.callAsSystem(() -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(type.storedWhenEnding.get())
            .as("endOfStream's side effects must follow the last step that can still fail the chunk")
            .isEqualTo("cursor-after-chunk");
        assertThat(persistedCursor().getCursorValue().asText()).isEqualTo("cursor-after-chunk");
    }

    @Test
    void a_refused_end_leaves_no_stored_position() {
        EndingType type = new EndingType(true);

        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(result).isInstanceOf(ChunkResult.Failure.class);
        assertThat(persistedCursor()).as("the next run reads the source from its start").isNull();
    }

    @BeforeEach
    void seed() {
        TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("tx-boundary-" + UUID.randomUUID());
            c.setTypeId("tx-boundary-probe");
            c.setConfig(Map.of());
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath("/default");
            ep.setEnabled(true);
            ep.setConfig(Map.of());
            endpoints.persist(ep);
            endpoints.flush();

            ConnectorRun r = new ConnectorRun();
            r.setTenantId(1L);
            r.setConnectorId(c.getId());
            r.setEndpointId(ep.getId());
            r.setStatus("Running");
            r.setSyncMode("Incremental");
            r.setStartedAt(Instant.now());
            r.setTriggeredBy("MANUAL");
            runs.persist(r);
            runs.flush();

            endpoint = ep;
            endpointId = ep.getId();
            run = r;
            runId = r.getId();
        }));
    }

    private ConnectorCursorEntity persistedCursor() {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            cursors.getEntityManager().clear();
            return cursors.findByEndpoint(endpointId);
        }));
    }

    private ConnectorRun persistedRun() {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            runs.getEntityManager().clear();
            return runs.findById(runId);
        }));
    }

    /** THE LATENCY PROPERTY: the source round-trip must not hold a pooled connection. */
    @Test
    void read_runs_with_no_transaction_active() {
        RecordingType type = new RecordingType(false);

        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(result).isInstanceOf(ChunkResult.Success.class);
        assertThat(type.txDuringRead.get())
            .as("reader.read() must run OUTSIDE any JTA transaction — it is a round-trip to the source system")
            .isFalse();
    }

    /** THE ATOMICITY PROPERTY: write + cursor + counters commit together. */
    @Test
    void write_cursor_and_counters_commit_together() {
        RecordingType type = new RecordingType(false);

        TenantContext.callAsSystem(() -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(type.txDuringWrite.get())
            .as("writer.write() must stay INSIDE the transaction that persists the cursor")
            .isTrue();
        assertThat(persistedCursor()).isNotNull();
        assertThat(persistedCursor().getCursorValue().asText()).isEqualTo("cursor-after-chunk");
        ConnectorRun after = persistedRun();
        assertThat(after.getRecordsIn()).isEqualTo(1);
        assertThat(after.getRecordsOut()).isEqualTo(1);
        assertThat(after.getChunkCount()).isEqualTo(1);
    }

    /**
     * THE SAFETY NET: a writer failure must never advance the cursor. A cursor persisted past
     * rows that were never written is silent, unrecoverable data loss.
     */
    @Test
    void writer_failure_does_not_advance_the_cursor() {
        RecordingType type = new RecordingType(true);

        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(type.writeCalled.get()).as("the writer must actually have been invoked").isTrue();
        assertThat(result).isInstanceOf(ChunkResult.Failure.class);
        ChunkResult.Failure f = (ChunkResult.Failure) result;
        assertThat(f.message()).isEqualTo("writer blew up");
        assertThat(f.retryable()).isFalse();

        assertThat(persistedCursor())
            .as("no cursor may be persisted when the write failed")
            .isNull();
        ConnectorRun after = persistedRun();
        assertThat(after.getRecordsIn()).isZero();
        assertThat(after.getRecordsOut()).isZero();
        assertThat(after.getChunkCount()).isZero();
    }

    /**
     * THE PREPARE LATENCY PROPERTY: {@code prepare()} — where a writer does its network work
     * (e.g. RagWriter's embedAll) — must run with NO transaction active, same as the read, and
     * strictly before {@code write()} so the write transaction never sees a pending network call.
     */
    @Test
    void prepare_runs_with_no_transaction_active_and_before_write() {
        RecordingType type = new RecordingType(false);

        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(result).isInstanceOf(ChunkResult.Success.class);
        assertThat(type.prepareCalled.get()).as("prepare() must have been invoked").isTrue();
        assertThat(type.txDuringPrepare.get())
            .as("writer.prepare() must run OUTSIDE any JTA transaction — it is network work, not a DB write")
            .isFalse();
        assertThat(type.prepareBeforeWrite.get())
            .as("prepare() must complete before write() starts")
            .isTrue();
    }

    /**
     * THE PREPARE SAFETY NET: a {@code prepare()} failure must fail the chunk loudly — through
     * the same {@link ChunkResult.Failure} path as a write failure — and never advance the
     * cursor. {@code write()} must never be reached once prepare() has already blown up.
     */
    @Test
    void prepare_failure_fails_the_chunk_and_does_not_advance_the_cursor() {
        RecordingType type = new RecordingType(false, true);

        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null));

        assertThat(type.prepareCalled.get()).as("the prepare hook must actually have been invoked").isTrue();
        assertThat(type.writeCalled.get()).as("write() must never run once prepare() has failed").isFalse();
        assertThat(result).isInstanceOf(ChunkResult.Failure.class);
        ChunkResult.Failure f = (ChunkResult.Failure) result;
        assertThat(f.message()).isEqualTo("prepare blew up");
        assertThat(f.retryable()).isFalse();

        assertThat(persistedCursor())
            .as("no cursor may be persisted when prepare() failed")
            .isNull();
        ConnectorRun after = persistedRun();
        assertThat(after.getRecordsIn()).isZero();
        assertThat(after.getRecordsOut()).isZero();
        assertThat(after.getChunkCount()).isZero();
    }

    /**
     * THE FUNNEL, one step earlier. A reader that cannot be BUILT must end the chunk exactly like a
     * reader that cannot READ: a {@link ChunkResult.Failure} carrying the refusal's own words.
     *
     * <p>Before this, the {@code Prepared} step sat OUTSIDE every try/catch in {@code executeChunk}
     * and the exception escaped the method entirely. The engine's last-resort catch then finalized
     * the run {@code Failed} while bypassing {@code failRun} — so no {@code connector.run.failed}
     * event was ever emitted — and logged "failed unexpectedly" with a stack trace, showing an
     * operator a crash where a configuration refusal was all that had happened.
     */
    @Test
    void a_reader_that_cannot_be_built_fails_the_chunk() {
        ChunkResult result = TenantContext.callAsSystem(
            () -> exec.executeChunk(run, new UnbuildableType(), endpoint, ConnectorCursor.empty(), null));

        assertThat(result).isInstanceOf(ChunkResult.Failure.class);
        ChunkResult.Failure f = (ChunkResult.Failure) result;
        assertThat(f.message()).isEqualTo(UnbuildableType.REFUSAL);
        assertThat(f.retryable()).isFalse();

        assertThat(persistedCursor())
            .as("nothing was read, so no position may be persisted")
            .isNull();
        assertThat(persistedRun().getChunkCount()).isZero();
    }

    /**
     * The SECOND symptom of the same hole, and the reason the catch must live INSIDE the lambda:
     * {@code QuarkusTransaction.joiningExisting()} marks the JOINED transaction rollback-only when
     * the callable throws — catching afterwards is already too late. A caller that holds a
     * transaction (every IT that drives a chunk, and any future in-transaction caller) would find
     * its own transaction poisoned by a refusal the chunk had already reported cleanly.
     *
     * <p>Nothing is caught here on purpose: should the refusal start escaping again, this test must
     * fail with that exception's own stack trace rather than with an assertion about a throwable it
     * captured. The {@code finally} is only there so a poisoned transaction cannot leak into the
     * next test.
     */
    @Test
    void a_reader_that_cannot_be_built_leaves_the_callers_transaction_usable() {
        Attempt attempt = TenantContext.callAsSystem(() -> {
            tm.begin();
            try {
                ChunkResult result = exec.executeChunk(run, new UnbuildableType(), endpoint,
                                                       ConnectorCursor.empty(), null);
                return new Attempt(result, tm.getStatus());
            } finally {
                tm.rollback();
            }
        });

        assertThat(attempt.result()).isInstanceOf(ChunkResult.Failure.class);
        assertThat(attempt.statusAfter())
            .as("the caller's transaction must still be ACTIVE — a refusal the chunk reported"
                + " cleanly must not leave it rollback-only")
            .isEqualTo(Status.STATUS_ACTIVE);
    }

    /** What one in-transaction attempt at a chunk leaves behind. */
    private record Attempt(ChunkResult result, int statusAfter) {}
}
