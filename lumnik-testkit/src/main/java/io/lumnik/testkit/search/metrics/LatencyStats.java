package io.lumnik.testkit.search.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** p95 by nearest-rank over a set of latency samples. */
public final class LatencyStats {

    private LatencyStats() {
    }

    public static double p95(List<Double> samplesMillis) {
        if (samplesMillis.isEmpty()) {
            throw new IllegalArgumentException("cannot compute p95 of an empty sample set");
        }
        List<Double> sorted = new ArrayList<>(samplesMillis);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        int index = Math.max(0, Math.min(sorted.size(), rank) - 1);
        return sorted.get(index);
    }
}
