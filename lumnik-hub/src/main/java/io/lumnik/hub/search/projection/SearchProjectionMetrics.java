package io.lumnik.hub.search.projection;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual {@code MeterRegistry} calls, no {@code @Timed} — the {@code IngestionMetrics}/{@code
 * FileSourceMetrics} idiom (design doc §6). No tenant or search-index tag anywhere in this class:
 * both are unbounded, business-controlled values.
 */
@ApplicationScoped
public class SearchProjectionMetrics {

    @Inject MeterRegistry registry;

    private final AtomicLong maxLagSeconds = new AtomicLong();
    private final AtomicLong laggingIndexes = new AtomicLong();

    @PostConstruct
    void registerGauges() {
        registry.gauge("lumnik_search_projection_lag_seconds", maxLagSeconds);
        registry.gauge("lumnik_search_projection_lagging_indexes", laggingIndexes);
    }

    /** Every terminal outcome of a rebuild (on-demand or scheduler-driven): ready, failed, cancelled. */
    public void recordRebuildTerminal(String outcome, Duration duration) {
        registry.counter("lumnik_search_rebuild_total", "outcome", outcome).increment();
        registry.timer("lumnik_search_rebuild_duration_seconds", "outcome", outcome)
            .record(duration);
    }

    /** Catch-up's own independent failure path — no run, no outcome label, just a count. */
    public void recordCatchupFailure() {
        registry.counter("lumnik_search_catchup_failures_total").increment();
    }

    /** The max lag and lagging-pair count across the WHOLE deployment (design doc §6) — never per
     *  tenant or per index, which would be an unbounded, business-identifying label. */
    public void recordLag(long maxLagSecondsValue, long laggingIndexCount) {
        maxLagSeconds.set(maxLagSecondsValue);
        laggingIndexes.set(laggingIndexCount);
    }
}
