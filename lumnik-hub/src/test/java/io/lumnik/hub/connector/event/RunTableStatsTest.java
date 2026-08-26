package io.lumnik.hub.connector.event;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the drain contract the engine's catch-all relies on: drain returns the recorded counts and
 * removes the run from the map, and a second drain is a harmless no-op (so the engine can drain on
 * every ending path — complete/fail/cancel and the unexpected-exception finally — without double-count
 * or leak).
 */
class RunTableStatsTest {

    @Test
    void drainReturnsCountsThenEmptiesTheRun() {
        RunTableStats stats = new RunTableStats();
        UUID run = UUID.randomUUID();
        stats.recordCounts(run, "core.t", 3, 1);
        stats.recordCounts(run, "core.t", 2, 0); // accumulates

        Map<String, Map<String, Long>> first = stats.drain(run);
        assertThat(first).containsKey("core.t");
        assertThat(first.get("core.t")).containsEntry("written", 5L).containsEntry("skipped", 1L);

        // Second drain: already removed → empty, never null (idempotent — the catch-all depends on this).
        assertThat(stats.drain(run)).isEmpty();
    }

    @Test
    void drainOfUnknownRunIsEmpty() {
        RunTableStats stats = new RunTableStats();
        assertThat(stats.drain(UUID.randomUUID())).isEmpty();
    }

    @Test
    void recordWithNullRunIdIsIgnored() {
        RunTableStats stats = new RunTableStats();
        stats.recordCounts(null, "core.t", 1, 0); // internal/test path without a run
        assertThat(stats.drain(null)).isEmpty();
    }
}
