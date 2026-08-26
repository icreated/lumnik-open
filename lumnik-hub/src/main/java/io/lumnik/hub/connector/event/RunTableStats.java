package io.lumnik.hub.connector.event;

import jakarta.enterprise.context.ApplicationScoped;
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

    public void recordCounts(UUID runId, String table, int written, int skipped) {
        if (runId == null) return; // internal/test write paths without a run
        Counts c = byRun.computeIfAbsent(runId, r -> new ConcurrentHashMap<>())
                        .computeIfAbsent(table, t -> new Counts());
        c.written().add(written);
        c.skipped().add(skipped);
    }

    /** Drain-and-clear; idempotent and null-safe (the engine's catch-all may drain on any path). */
    public Map<String, Map<String, Long>> drain(UUID runId) {
        if (runId == null) return Map.of(); // ConcurrentHashMap forbids null keys; mirror recordCounts()
        Map<String, Counts> m = byRun.remove(runId);
        if (m == null) return Map.of();
        Map<String, Map<String, Long>> out = new java.util.HashMap<>();
        m.forEach((t, c) -> out.put(t, Map.of("written", c.written().sum(), "skipped", c.skipped().sum())));
        return out;
    }
}
