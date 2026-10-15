package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.testkit.search.baseline.BaselineComparator;
import io.lumnik.testkit.search.baseline.BaselineRegressionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChunkBaselineGuardTest {

    static final ChunkBenchResult.FixtureIdentity ID = new ChunkBenchResult.FixtureIdentity(3, "digest", "sha256:v");

    static ChunkBenchResult result(String fingerprint, double exact, double paraphrase, double mixed,
            List<String> failures) {
        Map<String, ChunkBenchResult.ClassMetrics> perClass = new LinkedHashMap<>();
        perClass.put("exact-token", new ChunkBenchResult.ClassMetrics(20, exact, exact));
        perClass.put("paraphrase", new ChunkBenchResult.ClassMetrics(21, paraphrase, paraphrase));
        perClass.put("mixed", new ChunkBenchResult.ClassMetrics(21, mixed, mixed));
        double overall = (exact * 20 + paraphrase * 21 + mixed * 21) / 62;
        return new ChunkBenchResult("pgvector", 20, 5, fingerprint, ID, perClass,
            new ChunkBenchResult.ClassMetrics(62, overall, overall), List.of(), 1.0, true, failures);
    }

    private static final ChunkBenchResult BASE = result("sha256:a", 0.6, 0.7, 0.5, List.of());

    @Test
    void aMissingBaselineIsAHardFailureNamingTheUpdateCommand() {
        assertThatThrownBy(() -> ChunkBaselineGuard.assertNoRegression(Optional.empty(), BASE))
            .isInstanceOf(BaselineRegressionException.class)
            .hasMessageContaining("no baseline")
            .hasMessageContaining("-Dlumnik.search.updateBaseline=true");
    }

    @Test
    void anIdenticalRunPasses() {
        assertThatCode(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), BASE)).doesNotThrowAnyException();
    }

    @Test
    void anImprovementPasses() {
        ChunkBenchResult better = result("sha256:a", 0.7, 0.8, 0.6, List.of());
        assertThatCode(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), better)).doesNotThrowAnyException();
    }

    @Test
    void aDropInOneClassFailsEvenWhenTheOthersImprove() {
        ChunkBenchResult worseParaphrase = result("sha256:a", 0.9, 0.6, 0.9, List.of());
        assertThatThrownBy(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), worseParaphrase))
            .isInstanceOf(BaselineRegressionException.class)
            .hasMessageContaining("paraphrase")
            .hasMessageContaining("ndcgAt10");
    }

    @Test
    void aDropWithinTheToleranceIsAllowed() {
        ChunkBenchResult jitter = result("sha256:a", 0.595, 0.7, 0.5, List.of());
        assertThatCode(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), jitter)).doesNotThrowAnyException();
    }

    @Test
    void aChangedCorpusFingerprintMakesTheBaselineStale() {
        ChunkBenchResult otherCorpus = result("sha256:b", 0.6, 0.7, 0.5, List.of());
        assertThatThrownBy(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), otherCorpus))
            .isInstanceOf(BaselineRegressionException.class)
            .hasMessageContaining("stale");
    }

    @Test
    void aBaselineMeasuredOnOtherVectorsOrAnotherModelIsStale() {
        for (ChunkBenchResult.FixtureIdentity other : List.of(
                new ChunkBenchResult.FixtureIdentity(3, "digest", "sha256:other"),
                new ChunkBenchResult.FixtureIdentity(3, "another-digest", "sha256:v"),
                new ChunkBenchResult.FixtureIdentity(4, "digest", "sha256:v"))) {
            ChunkBenchResult onOtherFixture = new ChunkBenchResult(BASE.candidate(), BASE.warmupPasses(),
                BASE.measuredPasses(), BASE.corpusFingerprint(), other, BASE.perClass(), BASE.overall(),
                BASE.perQuery(), BASE.p95LatencyMillis(), BASE.stableAcrossPasses(), BASE.isolationFailures());
            assertThatThrownBy(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), onOtherFixture))
                .isInstanceOf(BaselineRegressionException.class)
                .hasMessageContaining("fixture");
        }
    }

    @Test
    void aRunWithIsolationFailuresNeverPasses() {
        ChunkBenchResult leaky = result("sha256:a", 0.6, 0.7, 0.5, List.of("LEAK pgvector: query q returned c-x"));
        assertThatThrownBy(() -> ChunkBaselineGuard.assertNoRegression(Optional.of(BASE), leaky))
            .isInstanceOf(BaselineRegressionException.class)
            .hasMessageContaining("isolation");
    }

    @Test
    void theUpdateCommandIsTheRepositoryOne() {
        assertThat(BaselineComparator.UPDATE_COMMAND).isEqualTo("-Dlumnik.search.updateBaseline=true");
    }
}
