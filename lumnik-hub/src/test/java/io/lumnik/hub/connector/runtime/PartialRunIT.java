package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunBound;
import io.lumnik.hub.connector.api.types.RunStart;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.RunRowFixtures.Seeded;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static io.lumnik.hub.connector.runtime.RunRowFixtures.cursorAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code RunStatus.Partial} has existed since the connector runtime was written and nothing ever
 * produced it. This is what produces it, and — as importantly — what does not.
 *
 * <p>Partial means ONE thing: the run stopped below the target it gave itself, a shortfall in
 * POSITION. A shortfall in DELIVERY — rows read and dropped — is not this; {@code records_skipped},
 * the dead-letter list and OTK Law 1 already say it in full, and a status answering both questions
 * would end up lying to one of them.
 *
 * <p>It has exactly two producers, both CERTAIN, neither inferring anything about the source:
 * <ol>
 *   <li>the reader stopped on a budget of its own while the target said there was more
 *       ({@link ReadResult.EmptyButOpen}), and</li>
 *   <li>the run began by skipping what the source had deleted underneath its stored cursor
 *       ({@link RunStart#lostAtStart()}) — the shape #467 measured and left reporting green.</li>
 * </ol>
 */
@QuarkusTest
class PartialRunIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject SchemaRefreshSpy refreshSpy;

    private RunRowFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new RunRowFixtures(connectors, endpoints, runs); }

    private static final List<Map<String, Object>> ONE_ROW = List.of(Map.of("k", "v"));

    @Test
    void a_reader_that_stops_on_its_own_budget_ends_the_run_partial_and_keeps_its_position() {
        ConnectorCursor reached = cursorAt(4200);
        ProbeType type = new ProbeType(n -> new ReadResult.EmptyButOpen<>(reached))
            .bounded("partition offsets [0: 9000]");
        Seeded s = fixtures.seed(type.id(), "partial-run-");

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status)
            .as("neither dry nor broken: the reader stopped, and the target says there is more")
            .isInstanceOf(RunStatus.Partial.class);
        assertThat(persistedStatus(s.runId()))
            .as("the run ROW carries it — the returned status is not what lm reads")
            .isEqualTo("Partial");
        assertThat(lastRunStatus(s.endpoint().getId()))
            .as("and so does the endpoint, stamped in the same transaction")
            .isEqualTo("Partial");
        assertThat(errorMessage(s.runId()))
            .as("the message names the target, so the operator knows what was not reached")
            .contains("partition offsets [0: 9000]");
        // Compared by VALUE, never node-to-node: this cursor went out a LongNode and comes back an
        // IntNode, which is half of the serialization boundary the no-progress net documents. The
        // first draft of this assertion compared the nodes and failed for that reason alone.
        assertThat(persistedCursor(s.endpoint().getId()))
            .as("THE POSITION IS KEPT. A reader reaches this ending having moved past records it "
                + "dead-lettered, and an advance the engine never stores is redone next run — the "
                + "same rows dead-lettered again, for ever (#467 paid for this on the other path)")
            .isNotNull();
        assertThat(persistedCursor(s.endpoint().getId()).get("offset").asLong())
            .isEqualTo(reached.value().get("offset").asLong());
    }

    @Test
    void a_run_that_began_past_deleted_records_is_partial_even_when_every_chunk_was_clean() {
        // #467's shape: cursor 0, the broker's log start moved to 2 000 → in=3000 out of 5 000,
        // skipped=0, and the run reported Completed. #467 gave the loss a WARN and left the status
        // green. The reading is correct — those offsets are gone — but the run did NOT read
        // everything its stored position was entitled to, and only the status can say so.
        ProbeType type = new ProbeType(n -> n == 1
                ? new ReadResult.HasMore<>(ONE_ROW, cursorAt(3000))
                : new ReadResult.EndOfStream<>(List.of()))
            .losing(List.of("t: partition 0 was at offset 0 and the log now starts at 2000 — "
                + "2000 records will never be read by this endpoint"));
        Seeded s = fixtures.seed(type.id(), "partial-run-");

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status).isInstanceOf(RunStatus.Partial.class);
        assertThat(persistedStatus(s.runId())).isEqualTo("Partial");
        assertThat(errorMessage(s.runId()))
            .as("the operator reads WHAT was lost, not merely that something was")
            .contains("will never be read");
    }

    @Test
    void a_reader_with_no_target_cannot_be_short_however_it_stops() {
        // THE RULE THIS PINS, and it lives HERE rather than in each reader: "short of the target"
        // needs a target. A reader that declares none — csv, rest, hello-world, and kafka whenever
        // the engine could not measure a head — ends the run the way it always did, even on the
        // ending that means "I stopped, the stream did not".
        //
        // Kafka used to make this call itself, choosing its result shape on whether its ceiling was
        // empty. That put one engine rule in two places, the second of them per-type.
        ProbeType type = new ProbeType(n -> new ReadResult.EmptyButOpen<>(cursorAt(9)));
        Seeded s = fixtures.seed(type.id(), "partial-run-");

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status)
            .as("no target declared, so nothing to fall short of")
            .isInstanceOf(RunStatus.Completed.class);
        assertThat(persistedStatus(s.runId())).isEqualTo("Completed");
        assertThat(persistedCursor(s.endpoint().getId()))
            .as("the position still travels — that was never the target's business")
            .isNotNull();
    }

    @Test
    void a_bounded_run_that_reads_its_way_to_a_dry_read_is_still_completed() {
        // THE GUARD. A target makes "short" nameable; it must not make "complete" rare. A bounded
        // run whose read simply comes back empty has the source's own answer and completes — the
        // ceiling is an upper bound on what is READABLE, so demanding the position meet it exactly
        // would report every healthy run against a transactional or compacted topic as partial.
        ProbeType type = new ProbeType(n -> n == 1
                ? new ReadResult.HasMore<>(ONE_ROW, cursorAt(17))
                : new ReadResult.EndOfStream<>(List.of()))
            .bounded("partition offsets [0: 9000]");
        Seeded s = fixtures.seed(type.id(), "partial-run-");
        refreshSpy.forget();

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status)
            .as("a dry read is the source's answer, and it is the only one available")
            .isInstanceOf(RunStatus.Completed.class);
        assertThat(persistedStatus(s.runId())).isEqualTo("Completed");
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(refreshSpy.saw(s.runId()))
                .as("the completed path has always asked for a refresh — this is the control")
                .isTrue());
    }

    @Test
    void a_partial_run_still_asks_for_its_schema_cards_to_be_refreshed() {
        // THE DEFECT THIS PINS, which was in the first draft of Partial: completeRun fires
        // ConnectorRunCompleted — the start of ask's schema-change look-back window — and carries
        // the per-table counters in its event. partialRun did neither. A Partial run WRITES rows
        // (#467's shape wrote 3 000 of 5 000), so its tables need indexing exactly as much, and the
        // Ask-to-SQL card would have gone stale for every partial run, silently. The two endings
        // share one method now; this is what says so out loud.
        ProbeType type = new ProbeType(n -> n == 1
                ? new ReadResult.HasMore<>(ONE_ROW, cursorAt(3000))
                : new ReadResult.EndOfStream<>(List.of()))
            .losing(List.of("2000 records will never be read by this endpoint"));
        Seeded s = fixtures.seed(type.id(), "partial-run-");
        refreshSpy.forget();

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status).isInstanceOf(RunStatus.Partial.class);
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(refreshSpy.saw(s.runId()))
                .as("a partial run wrote rows, so its schema cards must be refreshed too")
                .isTrue());
    }

    @Test
    void a_target_that_cannot_be_measured_degrades_to_an_unbounded_run_and_says_why() {
        // The policy: measuring a target is a DIAGNOSTIC, and a diagnostic must not be the thing
        // that turns a working ingestion into a failed run. So the run reads unbounded, exactly as
        // every run did before targets existed.
        //
        // The WARN names the exception's TYPE because the catch is deliberately wide enough to
        // absorb a broken config too, and getMessage() alone is null for an NPE — "reading
        // unbounded — null" told the operator nothing about which of the two had happened.
        ProbeType type = new ProbeType(n -> new ReadResult.EndOfStream<>(List.of()))
            .failingToMeasure(new NullPointerException());
        Seeded s = fixtures.seed(type.id(), "partial-run-");

        List<String> warnings = LogProbe.warnings(ConnectorEngine.class, () -> {
            RunStatus status = TenantContext.callAsSystem(
                () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));
            assertThat(status)
                .as("an unmeasurable target must not fail a run that can read")
                .isInstanceOf(RunStatus.Completed.class);
        });

        assertThat(warnings)
            .as("the operator reads WHICH failure it was, not a bare null: %s", warnings)
            .anySatisfy(w -> assertThat(w)
                .contains("reading unbounded")
                .contains("NullPointerException"));
    }

    @Test
    void a_source_the_diagnostic_proved_unreachable_fails_the_run_there_without_reading() {
        // The ONE exception to the policy above. Degrading is right when the diagnostic and the
        // read can disagree; it is waste when they cannot. Against a dead broker the measurement
        // spent its full metadata timeout to say "reading unbounded" and the reader spent the very
        // same timeout to learn the very same thing — 120 s and 198 client WARN lines for one
        // answer (#486). A type raises SourceUnreachableException only when it already HOLDS the
        // reader's answer, and the engine stops there.
        //
        // The reader below would have completed the run happily: so a Failed status here can only
        // have come from the diagnostic, and reads() == 0 says the second wait never began.
        ProbeType type = new ProbeType(n -> new ReadResult.EndOfStream<>(List.of()))
            .failingToMeasure(new SourceUnreachableException(
                "the kafka broker did not answer within the client's metadata timeout",
                new ConnectException("connection refused")));
        Seeded s = fixtures.seed(type.id(), "partial-run-");

        RunStatus status = TenantContext.callAsSystem(
            () -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));

        assertThat(status).isInstanceOf(RunStatus.Failed.class);
        RunStatus.Failed failed = (RunStatus.Failed) status;
        assertThat(failed.message()).contains("did not answer");
        // Retryable: a source being down is exactly the kind of failure a later run fixes.
        assertThat(failed.retryable()).isTrue();
        assertThat(type.reads())
            .as("the reader must not be given the same timeout to spend over again")
            .isZero();
    }


    /**
     * Reads a value back the way another connection would see it: a fresh transaction, with the L1
     * cache cleared first so the read is a real one and not the instance finalizeRun just wrote.
     * Four copies of this body is what the first version had.
     */
    private <T> T readCommitted(PanacheRepositoryBase<?, ?> repo, Supplier<T> read) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            repo.getEntityManager().clear();
            return read.get();
        }));
    }

    private String persistedStatus(UUID runId) {
        return readCommitted(runs, () -> runs.findById(runId).getStatus());
    }

    private String errorMessage(UUID runId) {
        return readCommitted(runs, () -> runs.findById(runId).getErrorMessage());
    }

    private String lastRunStatus(UUID endpointId) {
        return readCommitted(endpoints, () -> endpoints.findById(endpointId).getLastRunStatus());
    }

    private JsonNode persistedCursor(UUID endpointId) {
        return readCommitted(cursors, () -> {
            var ent = cursors.findByEndpoint(endpointId);
            return ent == null ? null : ent.getCursorValue();
        });
    }

    /** A reader whose every read is decided by a script, plus whatever the run start declares. */
    static final class ProbeType implements ConnectorType {
        private final IntFunction<ReadResult<Map<String, Object>>> script;
        private String target;
        private List<String> losses = List.of();
        private RuntimeException measurementFailure;
        private int reads;

        ProbeType(IntFunction<ReadResult<Map<String, Object>>> script) { this.script = script; }

        ProbeType bounded(String target) { this.target = target; return this; }

        ProbeType losing(List<String> losses) { this.losses = losses; return this; }

        ProbeType failingToMeasure(RuntimeException e) { this.measurementFailure = e; return this; }

        /** How many times the reader was asked for a chunk — 0 proves the run never reached it. */
        int reads() { return reads; }

        @Override public String id() { return "partial-run-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public RunStart runStart(ConnectorContext ctx, ConnectorCursor cursor) {
            if (measurementFailure != null) throw measurementFailure;
            return new RunStart(target == null ? null : () -> target, losses);
        }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            return (chunkSize, cursor, c) -> script.apply(++reads);
        }
    }
}
