package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunStart;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.RunRowFixtures.Seeded;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

import static io.lumnik.hub.connector.runtime.RunRowFixtures.cursorAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #898: {@code runChunks} owns the run's {@code RunScope}. A resource a type asks for at run start and in every
 * chunk is built once and closed once, on every ending, after the row carries its terminal status; a close that
 * fails changes nothing the operator sees; an {@code Error} that leaves the run still clears the live-run entry.
 */
@QuarkusTest
class RunScopeEngineIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;

    private RunRowFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new RunRowFixtures(connectors, endpoints, runs); }

    private static final List<Map<String, Object>> ONE_ROW = List.of(Map.of("k", "v"));

    @Test
    void a_resource_asked_for_at_run_start_and_in_every_chunk_is_built_once_and_closed_once() {
        ScopeType type = new ScopeType(n -> n < 3 ? new ReadResult.HasMore<>(ONE_ROW, cursorAt(n))
            : new ReadResult.EndOfStream<>(List.of())).asksAtRunStart();
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        RunStatus status = run(s, type);

        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        assertThat(type.built).as("one build for the run start and all three chunks").isEqualTo(1);
        assertThat(type.seen).as("run start + 3 chunks asked").hasSize(4)
            .allSatisfy(h -> assertThat(h).isSameAs(type.seen.get(0)));
        assertThat(type.closes).isEqualTo(1);
        assertThat(type.closedWhileRowWas).as("closed after the row said so").containsExactly("Completed");
    }

    @Test
    void a_failed_run_closes_it_after_the_row_says_Failed() {
        ScopeType type = new ScopeType(n -> {
            if (n == 1) return new ReadResult.HasMore<>(ONE_ROW, cursorAt(1));
            throw new IllegalStateException("the source went away");
        });
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        RunStatus status = run(s, type);

        assertThat(status).isInstanceOf(RunStatus.Failed.class);
        assertThat(type.closes).isEqualTo(1);
        assertThat(type.closedWhileRowWas).containsExactly("Failed");
    }

    @Test
    void a_cancelled_run_closes_it_after_the_row_says_Cancelled() {
        ScopeType type = new ScopeType(null);
        type.script = n -> {
            engine.cancel(type.runId);
            return new ReadResult.HasMore<>(ONE_ROW, cursorAt(1));
        };
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        RunStatus status = run(s, type);

        assertThat(status).isInstanceOf(RunStatus.Cancelled.class);
        assertThat(type.closes).isEqualTo(1);
        assertThat(type.closedWhileRowWas).containsExactly("Cancelled");
    }

    @Test
    void a_partial_run_closes_it_after_the_row_says_Partial() {
        ScopeType type = new ScopeType(n -> new ReadResult.EmptyButOpen<>(cursorAt(4200))).bounded("offsets [0: 9000]");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        RunStatus status = run(s, type);

        assertThat(status).isInstanceOf(RunStatus.Partial.class);
        assertThat(type.closes).isEqualTo(1);
        assertThat(type.closedWhileRowWas).containsExactly("Partial");
    }

    @Test
    void a_run_start_that_proves_the_source_unreachable_still_closes_what_it_opened() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart()
            .failingAtRunStart(new SourceUnreachableException("the broker did not answer", new ConnectException("refused")));
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        RunStatus status = run(s, type);

        assertThat(status).isInstanceOf(RunStatus.Failed.class);
        assertThat(type.reads).as("the reader never ran").isZero();
        assertThat(type.closes).isEqualTo(1);
        assertThat(type.closedWhileRowWas).containsExactly("Failed");
    }

    @Test
    void a_close_that_fails_leaves_the_status_alone_and_is_logged() {
        ScopeType type = new ScopeType(n -> n == 1 ? new ReadResult.HasMore<>(ONE_ROW, cursorAt(1))
            : new ReadResult.EndOfStream<>(List.of()));
        type.closeFailure = new IllegalStateException("the session would not close");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        List<String> warnings = LogProbe.warnings(OwnedRunScope.class, () -> {
            RunStatus status = run(s, type);
            assertThat(status).as("the failed close does not rewrite the ending").isInstanceOf(RunStatus.Completed.class);
        });

        assertThat(fixtures.persistedStatus(s.runId())).isEqualTo("Completed");
        assertThat(warnings).as("the failure is visible: %s", warnings)
            .anySatisfy(w -> assertThat(w).contains(s.runId().toString()).contains("the session would not close"));
    }

    @Test
    void a_linkage_error_out_of_a_close_does_not_turn_a_finished_run_into_a_crash() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart();
        type.closeFailure = new NoClassDefFoundError("com/acme/Client");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        List<String> warnings = LogProbe.warnings(OwnedRunScope.class, () -> {
            RunStatus status = run(s, type);
            assertThat(status).as("the result of a run that finished stays the result").isInstanceOf(RunStatus.Completed.class);
        });

        assertThat(fixtures.persistedStatus(s.runId())).isEqualTo("Completed");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("com/acme/Client"));
    }

    @Test
    void two_runs_do_not_share_a_scope() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart();
        run(fixtures.seed(type.id(), "run-scope-"), type);
        run(fixtures.seed(type.id(), "run-scope-"), type);

        assertThat(type.built).isEqualTo(2);
        assertThat(type.closes).isEqualTo(2);
    }

    @Test
    void an_error_escaping_the_run_still_closes_the_scope_and_clears_the_live_entry() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart();
        type.runStartFailure = new LinkageError("not a class the run can survive");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        assertThatThrownBy(() -> run(s, type)).isInstanceOf(LinkageError.class);

        assertThat(type.closes).as("released as soon as runChunks was done with it").isEqualTo(1);
        assertThat(engine.cancel(s.runId())).as("a leaked live-run entry would answer true").isFalse();
        assertThat(fixtures.persistedStatus(s.runId())).as("an Error skips the outer net: the reaper takes the row").isEqualTo("Running");
        abandon(s);
    }

    @Test
    void an_error_out_of_a_close_does_not_replace_the_exception_already_escaping() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart();
        type.runStartFailure = new LinkageError("primary");
        type.closeFailure = new InternalError("close");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        assertThatThrownBy(() -> run(s, type))
            .as("the failure that ended the run stays the one the caller sees")
            .isInstanceOf(LinkageError.class).hasMessage("primary")
            .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("close"));

        assertThat(type.closes).isEqualTo(1);
        assertThat(engine.cancel(s.runId())).isFalse();
        abandon(s);
    }

    @Test
    void an_error_out_of_a_close_still_clears_the_live_entry_and_the_others_still_close() {
        ScopeType type = new ScopeType(n -> new ReadResult.EndOfStream<>(List.of())).asksAtRunStart().asksForAnotherFirst();
        type.closeFailure = new InternalError("boom");
        Seeded s = fixtures.seed(type.id(), "run-scope-");

        assertThatThrownBy(() -> run(s, type)).isInstanceOf(InternalError.class).hasMessage("boom");

        assertThat(type.otherCloses).as("the resource built first still got its close").isEqualTo(1);
        assertThat(engine.cancel(s.runId())).as("a leaked live-run entry would answer true").isFalse();
        assertThat(fixtures.persistedStatus(s.runId())).isEqualTo("Completed");
    }

    /** A run an Error left Running is the reaper's to fail; do it here so no later test meets the stray row. */
    private void abandon(Seeded s) {
        TenantContext.callAsSystem(() -> engine.finalizeRun(s.runId(), ConnectorRunStatus.FAILED, "test cleanup", null, Instant.now()));
    }

    private RunStatus run(Seeded s, ScopeType type) {
        type.runId = s.runId();
        return TenantContext.callAsSystem(() -> engine.runChunks(s.connector(), s.endpoint(), s.runId(), type));
    }

    /** A type whose reader, and optionally run start, ask the scope for one resource and record what they saw. */
    final class ScopeType implements ConnectorType {
        IntFunction<ReadResult<Map<String, Object>>> script;
        final List<Held> seen = new ArrayList<>();
        final List<String> closedWhileRowWas = new ArrayList<>();
        UUID runId;
        int built, closes, reads, otherCloses;
        boolean askAtRunStart, askForAnotherFirst;
        String target;
        Throwable runStartFailure;
        Throwable closeFailure;

        ScopeType(IntFunction<ReadResult<Map<String, Object>>> script) { this.script = script; }

        ScopeType asksAtRunStart() { askAtRunStart = true; return this; }
        ScopeType asksForAnotherFirst() { askForAnotherFirst = true; return this; }
        ScopeType bounded(String target) { this.target = target; return this; }
        ScopeType failingAtRunStart(Throwable e) { runStartFailure = e; return this; }

        @Override public String id() { return "run-scope-probe"; }
        @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
        @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
        @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

        @Override public RunStart runStart(ConnectorContext ctx, ConnectorCursor cursor) {
            if (askForAnotherFirst) ctx.scope().resource("other", () -> (AutoCloseable) () -> otherCloses++);
            if (askAtRunStart) seen.add(ctx.scope().resource("probe", Held::new));
            throwIfSet(runStartFailure);
            return new RunStart(target == null ? null : () -> target, List.of());
        }

        @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
            return (chunkSize, cursor, c) -> {
                seen.add(c.scope().resource("probe", Held::new));
                return script.apply(++reads);
            };
        }

        private static void throwIfSet(Throwable t) {
            if (t instanceof Error e) throw e;
            if (t instanceof RuntimeException e) throw e;
        }

        /** What a transport would hold: counts its builds and, when closed, reads the row the way another connection would. */
        final class Held implements AutoCloseable {
            Held() { built++; }
            @Override public void close() {
                closes++;
                closedWhileRowWas.add(fixtures.persistedStatus(runId));
                throwIfSet(closeFailure);
            }
        }
    }
}
