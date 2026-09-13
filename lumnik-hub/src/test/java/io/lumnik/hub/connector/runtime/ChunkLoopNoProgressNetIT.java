package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.jdbc.JdbcCursor;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE NO-PROGRESS NET: a chunk that reads records without moving the stored position must end the
 * run, not go round again. Without it a reader that re-reads its own window loops for as long as
 * the pod lives — found live on a 700-row table that had read 2 279 500 rows in one minute before
 * it was cancelled by hand.
 *
 * <p>The net used to compare the cursor READ FROM POSTGRES with the one the reader had just BUILT
 * IN THE JVM. That comparison crosses a serialization boundary, and Jackson says "different" for
 * two nodes that hold the same value on either side of it:
 * <ul>
 *  <li>{@code POJONode} — what {@code putPOJO} writes, and what every jdbc cursor is made of.
 *      {@code POJONode.equals} demands a {@code POJONode} in return, so it is false against
 *      anything that came back out of jsonb. ALWAYS false: the net was dead for jdbc.</li>
 *  <li>{@code LongNode} vs {@code IntNode} — a cursor built with {@code put(name, 42L)} comes back
 *      from jsonb as an {@code IntNode}, and Jackson's numeric nodes compare by class.</li>
 * </ul>
 *
 * <p>So the shapes are the parameters here. The third, all-text, is the one that already compared
 * equal through jsonb — it pins that the working case keeps working, and it is why the net looked
 * alive when it was dead for two thirds of what the product writes.
 *
 * <p>The run ends FAILED — but only for a chunk that said MORE IS COMING. A reader is allowed to
 * end holding records and persist no position ({@code EndOfStream} with a loaded list): the local
 * csv glob does exactly that, and {@code ReadResult}'s javadoc documents the re-read it costs. That
 * is a HEALTHY ending, and {@link ChunkResult.Success#endOfStream()} is how a chunk says so — the
 * engine used to infer it from the cursor comparison, which is why removing that comparison turned
 * 22 csv runs red before the gate caught it. What is left at the net is a chunk that promised more
 * and did not move: no shipped reader does that, so calling it "Completed" would hide a re-reading
 * endpoint behind a green run.
 *
 * <p>The stuck reader stops after {@link #READ_CAP} reads. A dead net must FAIL this test, never
 * hang the suite.
 */
@QuarkusTest
class ChunkLoopNoProgressNetIT {

    /** Reads a run needs when the net works: one that advances, one that does not. */
    private static final int EXPECTED_READS = 2;

    /** The dead-net escape hatch — comfortably above {@link #EXPECTED_READS}. */
    private static final int READ_CAP = 8;

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;

    static Stream<Arguments> stuckCursors() {
        var m = new ObjectMapper();
        return Stream.of(
            Arguments.of("jdbc — putPOJO, the shape that ran away",
                new JdbcCursor("", null, Map.of("id", 7L)).toConnectorCursor()),
            Arguments.of("numeric — LongNode out, IntNode back",
                new ConnectorCursor(m.createObjectNode().put("offset", 42L), Instant.EPOCH)),
            Arguments.of("text — the shape that already compared equal",
                new ConnectorCursor(m.createObjectNode().put("page", "last"), Instant.EPOCH)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stuckCursors")
    void a_chunk_that_reads_without_advancing_ends_the_run(String shape, ConnectorCursor stuck) {
        ProbeType type = stuckAt(stuck);
        Seeded s = seed(type.id());

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(type.reads.get())
            .as("the loop must stop at the first chunk that read records without moving the "
                + "stored position — %s", shape)
            .isEqualTo(EXPECTED_READS);
        assertThat(status)
            .as("a position that does not move is always pathological — %s", shape)
            .isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) status).message())
            .as("the refusal must name the endpoint that is stuck and what it did")
            .contains(s.endpoint().getName())
            .contains("did not move");
        assertThat(persistedStatus(s.runId()))
            .as("the run ROW carries the ending too — the returned status is not what lm reads")
            .isEqualTo("Failed");
    }

    /**
     * The shape that broke when the cursor comparison went away: records in hand, no position
     * persisted, and the stream over.
     */
    @Test
    void a_loaded_end_of_stream_completes_the_run() {
        ProbeType type = new ProbeType(n -> new ReadResult.EndOfStream<>(ONE_ROW));
        Seeded s = seed(type.id());

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(type.reads.get())
            .as("a terminal read ends the run — it must not be read a second time")
            .isEqualTo(1);
        assertThat(status)
            .as("ending loaded with no persisted position is a HEALTHY ending, not a stuck one")
            .isInstanceOf(RunStatus.Completed.class);
        assertThat(persistedStatus(s.runId())).isEqualTo("Completed");
    }

    private String persistedStatus(UUID runId) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            runs.getEntityManager().clear();
            return runs.findById(runId).getStatus();
        }));
    }

    private static final List<Map<String, Object>> ONE_ROW = List.of(Map.of("k", "v"));

    /** A reader whose every read is decided by a script, keyed on the read number (1-based). */
    static final class ProbeType implements ConnectorType {
        private final IntFunction<ReadResult<Map<String, Object>>> script;
        final AtomicInteger reads = new AtomicInteger();

        ProbeType(IntFunction<ReadResult<Map<String, Object>>> script) { this.script = script; }

        @Override public String id() { return "chunk-loop-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            return (chunkSize, cursor, c) -> script.apply(reads.incrementAndGet());
        }
    }

    /**
     * Always the SAME position, with more promised — what a jdbc endpoint does once it starts
     * declaring its own persisted cursor foreign. Capped so a dead net fails instead of hanging.
     */
    private static ProbeType stuckAt(ConnectorCursor stuck) {
        return new ProbeType(n -> n > READ_CAP
            ? new ReadResult.EndOfStream<>(List.of())
            : new ReadResult.HasMore<>(ONE_ROW, stuck));
    }

    private record Seeded(Connector connector, ConnectorEndpoint endpoint, UUID runId) {}

    private Seeded seed(String typeId) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("no-progress-" + UUID.randomUUID());
            c.setTypeId(typeId);
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

            return new Seeded(c, ep, r.getId());
        }));
    }
}
