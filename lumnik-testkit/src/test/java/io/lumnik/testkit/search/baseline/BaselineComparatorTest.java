package io.lumnik.testkit.search.baseline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BaselineComparatorTest {

    private static BaselineSnapshot snapshot(String fingerprint, double ndcg, double recall) {
        return new BaselineSnapshot(1, fingerprint, new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(ndcg, recall, 5.0));
    }

    @Test
    void failsWithTheEstablishCommandWhenNoBaselineExistsYet() {
        BaselineSnapshot candidate = snapshot("sha256:abc", 0.9, 0.9);

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.empty(), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("-Dlumnik.search.updateBaseline=true");
    }

    @Test
    void failsWhenTheStoredBaselineIsForADifferentProvider() {
        BaselineSnapshot stored = new BaselineSnapshot(1, "sha256:same",
                new BaselineSnapshot.ProviderInfo("postgres", "1"),
                new BaselineSnapshot.Metrics(0.9, 0.9, 5.0));
        BaselineSnapshot candidate = snapshot("sha256:same", 0.9, 0.9); // provider "reference"

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("reference");
    }

    @Test
    void failsWhenTheStoredBaselineHasADifferentSchemaVersionThanTheCandidate() {
        BaselineSnapshot stored = new BaselineSnapshot(1, "sha256:same",
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(0.9, 0.9, 5.0));
        BaselineSnapshot candidate = new BaselineSnapshot(2, "sha256:same",
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(0.9, 0.9, 5.0));

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void failsWhenTheCorpusFingerprintNoLongerMatchesTheStoredBaseline() {
        BaselineSnapshot stored = snapshot("sha256:old", 0.9, 0.9);
        BaselineSnapshot candidate = snapshot("sha256:new", 0.9, 0.9);

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void failsWhenNdcgDropsMoreThanTheTolerance() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90);
        BaselineSnapshot candidate = snapshot("sha256:same", 0.88, 0.90);

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("ndcgAt10");
    }

    @Test
    void passesWhenNdcgDropIsWithinTolerance() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90);
        BaselineSnapshot candidate = snapshot("sha256:same", 0.895, 0.90);

        assertThatCode(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWhenRecallDropsMoreThanTheTolerance() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90);
        BaselineSnapshot candidate = snapshot("sha256:same", 0.90, 0.80);

        assertThatThrownBy(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("recallAt10");
    }

    @Test
    void passesWhenNdcgImproves() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90);
        BaselineSnapshot candidate = snapshot("sha256:same", 0.95, 0.90);

        assertThatCode(() -> BaselineComparator.assertNoRegression(Optional.of(stored), candidate))
                .doesNotThrowAnyException();
    }

    @Test
    void latencyCheckFailsWhenCandidateExceedsRelativeToleranceAndFloor() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90); // p95 = 5.0ms
        BaselineSnapshot candidate = new BaselineSnapshot(1, "sha256:same",
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(0.90, 0.90, 100.0));

        assertThatThrownBy(() -> BaselineComparator.assertNoLatencyRegression(stored, candidate, 1.20, 5.0))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("p95LatencyMillis");
    }

    @Test
    void latencyCheckPassesWithinRelativeToleranceOrFloor() {
        BaselineSnapshot stored = snapshot("sha256:same", 0.90, 0.90); // p95 = 5.0ms
        BaselineSnapshot candidate = new BaselineSnapshot(1, "sha256:same",
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(0.90, 0.90, 5.9));

        assertThatCode(() -> BaselineComparator.assertNoLatencyRegression(stored, candidate, 1.20, 5.0))
                .doesNotThrowAnyException();
    }
}
