package io.lumnik.hub.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.testkit.cdi.EmptyInstance;
import io.lumnik.testkit.cdi.ListInstance;
import io.lumnik.testkit.cdi.SingletonInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredProviderResolverTest {

    private static class NamedPort implements IndexedSearchPort {
        private final String name;
        NamedPort(String name) { this.name = name; }
        public String name() { return name; }
        public ProviderStatus status() { return ProviderStatus.available(); }
        public IndexedSearchResult search(IndexedSearchQuery query) { return IndexedSearchResult.empty(); }
    }

    private static class NamedPortWithSink extends NamedPort implements ProjectionSink {
        NamedPortWithSink(String name) { super(name); }
        public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) { return "g"; }
        public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {}
        public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}
        public void publish(long tenantId, String searchIndex, String generation) {}
        public void abandon(long tenantId, String searchIndex, String generation) {}
        public void purgeIndex(long tenantId, String searchIndex) {}
        public long liveDocumentCount(long tenantId, String searchIndex) { return 0; }
    }

    @Test
    void failsLoudlyWhenNoProviderMatchesTheConfiguredName() {
        assertThatThrownBy(() -> ConfiguredProviderResolver.resolve(new EmptyInstance<>(), "postgres"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no provider found for configured name")
            .hasMessageContaining("postgres");
    }

    @Test
    void failsLoudlyWhenTheMatchDoesNotImplementProjectionSink() {
        NamedPort portOnly = new NamedPort("postgres");
        assertThatThrownBy(() -> ConfiguredProviderResolver.resolve(new SingletonInstance<>(portOnly), "postgres"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not implement ProjectionSink")
            .hasMessageContaining("postgres");
    }

    @Test
    void failsLoudlyWhenTwoProvidersShareTheConfiguredName() {
        NamedPortWithSink first = new NamedPortWithSink("postgres");
        NamedPortWithSink second = new NamedPortWithSink("postgres");
        assertThatThrownBy(() -> ConfiguredProviderResolver.resolve(
                new ListInstance<>(List.of(first, second)), "postgres"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("more than one provider")
            .hasMessageContaining("postgres");
    }

    @Test
    void aProviderWhoseNameThrowsNeverMatches() {
        NamedPortWithSink throwing = new NamedPortWithSink("postgres") {
            @Override public String name() { throw new RuntimeException("boom"); }
        };
        assertThatThrownBy(() -> ConfiguredProviderResolver.resolve(new SingletonInstance<>(throwing), "postgres"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no provider found for configured name");
    }

    @Test
    void resolvesCleanlyWhenExactlyOneMatchingProviderImplementsBoth() {
        NamedPortWithSink both = new NamedPortWithSink("postgres");
        ConfiguredProviderResolver.Resolved resolved =
            ConfiguredProviderResolver.resolve(new SingletonInstance<>(both), "postgres");
        assertThat(resolved.port()).isSameAs(both);
        assertThat(resolved.sink()).isSameAs(both);
        assertThat(resolved.name()).isEqualTo("postgres");
    }
}
