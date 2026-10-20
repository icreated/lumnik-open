package io.lumnik.hub.connector.event;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Per-run, per-table write counters. Safe in-JVM: runs are advisory-locked to one instance. */
@ApplicationScoped
public class RunTableStats {

    public record Counts(LongAdder written, LongAdder skipped) {
        Counts() { this(new LongAdder(), new LongAdder()); }
    }

    private final Map<UUID, Map<String, Counts>> byRun = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> removedByRun = new ConcurrentHashMap<>();

    public void recordCounts(UUID runId, String table, int written, int skipped) {
        if (runId == null) return; // internal/test write paths without a run
        Counts c = byRun.computeIfAbsent(runId, r -> new ConcurrentHashMap<>())
                        .computeIfAbsent(table, t -> new Counts());
        c.written().add(written);
        c.skipped().add(skipped);
    }

    /** Rows a writer removed while writing (#1196: a jdbc table's move to one row per key). */
    public void recordRemoved(UUID runId, long rows) {
        if (runId == null || rows == 0) return;
        removedByRun.computeIfAbsent(runId, r -> new LongAdder()).add(rows);
    }

    /** The rows removed since the last call, for the run's counter; 0 when none. */
    public long takeRemoved(UUID runId) {
        if (runId == null) return 0;
        LongAdder removed = removedByRun.remove(runId);
        return removed == null ? 0 : removed.sum();
    }

    /** Drain-and-clear; idempotent and null-safe (the engine's catch-all may drain on any path). */
    public Map<String, Map<String, Long>> drain(UUID runId) {
        if (runId == null) return Map.of(); // ConcurrentHashMap forbids null keys; mirror recordCounts()
        removedByRun.remove(runId);
        Map<String, Counts> m = byRun.remove(runId);
        if (m == null) return Map.of();
        Map<String, Map<String, Long>> out = new HashMap<>();
        m.forEach((t, c) -> out.put(t, Map.of("written", c.written().sum(), "skipped", c.skipped().sum())));
        return out;
    }
}
