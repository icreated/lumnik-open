package io.lumnik.hub.search.projection;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProjectionMetricsTest {

    @Test
    void recordsRebuildOutcomesAndDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchProjectionMetrics metrics = new SearchProjectionMetrics();
        metrics.registry = registry;

        metrics.recordRebuildTerminal("ready", Duration.ofSeconds(5));
        metrics.recordRebuildTerminal("failed", Duration.ofSeconds(1));
        metrics.recordRebuildTerminal("ready", Duration.ofSeconds(3));

        assertThat(registry.counter("lumnik_search_rebuild_total", "outcome", "ready").count()).isEqualTo(2.0);
        assertThat(registry.counter("lumnik_search_rebuild_total", "outcome", "failed").count()).isEqualTo(1.0);
        assertThat(registry.timer("lumnik_search_rebuild_duration_seconds", "outcome", "ready").count()).isEqualTo(2);
    }

    @Test
    void recordsCatchupFailuresWithNoLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchProjectionMetrics metrics = new SearchProjectionMetrics();
        metrics.registry = registry;

        metrics.recordCatchupFailure();
        metrics.recordCatchupFailure();

        assertThat(registry.counter("lumnik_search_catchup_failures_total").count()).isEqualTo(2.0);
    }
}
