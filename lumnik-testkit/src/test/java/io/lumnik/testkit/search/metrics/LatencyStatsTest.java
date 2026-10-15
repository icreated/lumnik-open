package io.lumnik.testkit.search.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

    @Test
    void computesP95ByNearestRankOverTenSamples() {
        List<Double> samples = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);

        double p95 = LatencyStats.p95(samples);

        assertThat(p95).isEqualTo(10.0);
    }

    @Test
    void failsFastOnAnEmptySampleSet() {
        assertThatThrownBy(() -> LatencyStats.p95(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
