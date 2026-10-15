package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #671 write-side discovery as executable specification: mirrors {@link SearchGateTest} exactly,
 * against the same resolution rules {@link SearchGate} enforces on the read side.
 */
class ProjectionSinkGateTest {

    @Test
    void zeroInstalledResolvesEmpty() {
        ProjectionSinkGate gate = gateOf(null);
        assertThat(gate.resolve()).isEmpty();
    }

    @Test
    void exactlyOneInstalledUnconfiguredResolvesIt() {
        FakeSink sink = new FakeSink("postgres");
        ProjectionSinkGate gate = gateOf(null, sink);
        assertThat(gate.resolve()).contains(sink);
    }

    @Test
    void configuredNameSelectsMatchingProvider() {
        FakeSink a = new FakeSink("postgres");
        FakeSink b = new FakeSink("opensearch");
        ProjectionSinkGate gate = gateOf("opensearch", a, b);
        assertThat(gate.resolve()).contains(b);
    }

    @Test
    void configuredNameMatchingNothingResolvesEmpty() {
        ProjectionSinkGate gate = gateOf("nope", new FakeSink("postgres"));
        assertThat(gate.resolve()).isEmpty();
    }

    @Test
    void ambiguousUnconfiguredResolvesEmpty() {
        ProjectionSinkGate gate = gateOf(null, new FakeSink("postgres"), new FakeSink("opensearch"));
        assertThat(gate.resolve()).isEmpty();
    }

    @Test
    void providerThatThrowsFromNameIsTreatedAsAbsentNotCrashing() {
        ProjectionSink throwing = new FakeSink(null) {
            @Override public String name() { throw new RuntimeException("boom"); }
        };
        ProjectionSinkGate gate = gateOf(null, throwing);
        assertThat(gate.resolve()).isEmpty();
    }

    /** Minor #2 (#671 final review): the ambiguity check counts every INSTALLED bean, including one
     *  whose own name() throws — SearchGate's own raw installed-provider count, not just the ones
     *  that resolved a name — so the two gates can never disagree about ambiguity for the same set
     *  of installed beans. Before the fix this resolved the one healthy sink instead of refusing. */
    @Test
    void twoInstalledOneThrowingFromNameIsStillAmbiguousNotResolvedToTheHealthyOne() {
        ProjectionSink throwing = new FakeSink(null) {
            @Override public String name() { throw new RuntimeException("boom"); }
        };
        ProjectionSinkGate gate = gateOf(null, throwing, new FakeSink("postgres"));
        assertThat(gate.resolve()).isEmpty();
    }

    /** A gate wired by hand, like {@link SearchGateTest#gate}: a CDI mock satisfies the Instance. */
    @SuppressWarnings("unchecked")
    private static ProjectionSinkGate gateOf(String configured, ProjectionSink... installed) {
        ProjectionSinkGate g = new ProjectionSinkGate();
        Instance<ProjectionSink> providers = Mockito.mock(Instance.class);
        Mockito.when(providers.iterator()).thenAnswer(inv -> List.of(installed).iterator());
        g.providers = providers;
        g.configuredProvider = Optional.ofNullable(configured);
        return g;
    }

    private static class FakeSink implements ProjectionSink {
        private final String name;
        FakeSink(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public ProviderStatus status() { return null; }
        @Override public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) { return "gen"; }
        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {}
        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}
        @Override public void publish(long tenantId, String searchIndex, String generation) {}
        @Override public void abandon(long tenantId, String searchIndex, String generation) {}
        @Override public void purgeIndex(long tenantId, String searchIndex) {}
        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
