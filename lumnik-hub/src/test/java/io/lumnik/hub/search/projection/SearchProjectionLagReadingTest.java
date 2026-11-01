package io.lumnik.hub.search.projection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two lag gauges' arithmetic, on the pairs {@code oldestPendingMarkedAtAdmin} returns (#703), without the shared
 * database: the gauges are deployment-wide, and under {@code -Psearch} the projection worker drains other tests'
 * pairs behind any exact count read from them (#1661).
 */
class SearchProjectionLagReadingTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void aPairIsLaggingBecauseItHasAPendingMarkNotBecauseTheMarkIsOld() {
        // oldestPendingMarkedAtAdmin only returns a pair that HAS pending work: a mark from the same second counts.
        var reading = SearchProjectionWorker.lagOf(Map.of(1L, Map.of("idx", NOW)), NOW);

        assertThat(reading.laggingIndexes()).isEqualTo(1);
        assertThat(reading.maxSeconds()).isZero();
    }

    @Test
    void theMaxIsTheLargestLagAcrossTenantsAndEveryPairCounts() {
        var reading = SearchProjectionWorker.lagOf(Map.of(
            1L, Map.of("a", NOW.minusSeconds(10_000), "b", NOW.minusSeconds(5)),
            2L, Map.of("c", NOW.minusSeconds(60))), NOW);

        assertThat(reading.maxSeconds()).isEqualTo(10_000);
        assertThat(reading.laggingIndexes()).isEqualTo(3);
    }

    @Test
    void nothingPendingReadsZero() {
        var reading = SearchProjectionWorker.lagOf(Map.of(), NOW);

        assertThat(reading.maxSeconds()).isZero();
        assertThat(reading.laggingIndexes()).isZero();
    }

    /** A mark stamped ahead of this node's clock is a lag of zero, not a negative gauge. */
    @Test
    void aMarkFromTheFutureIsNotANegativeLag() {
        var reading = SearchProjectionWorker.lagOf(Map.of(1L, Map.of("idx", NOW.plusSeconds(30))), NOW);

        assertThat(reading.maxSeconds()).isZero();
        assertThat(reading.laggingIndexes()).isEqualTo(1);
    }
}
