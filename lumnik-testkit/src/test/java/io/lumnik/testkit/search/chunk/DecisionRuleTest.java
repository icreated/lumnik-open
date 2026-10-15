package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionRuleTest {

    private static final ChunkBenchResult BASE = ChunkBaselineGuardTest.result("sha256:a", 0.50, 0.70, 0.40, List.of());

    private static ChunkBenchResult candidate(String name, double exact, double paraphrase, double mixed,
            List<String> failures) {
        ChunkBenchResult r = ChunkBaselineGuardTest.result("sha256:a", exact, paraphrase, mixed, failures);
        return new ChunkBenchResult(name, r.warmupPasses(), r.measuredPasses(), r.corpusFingerprint(), r.fixture(), r.perClass(),
            r.overall(), r.perQuery(), r.p95LatencyMillis(), r.stableAcrossPasses(), r.isolationFailures());
    }

    private static ChunkBenchResult strong(String name) {
        return candidate(name, 0.60, 0.70, 0.50, List.of());
    }

    private static ChunkBenchResult weak(String name) {
        return candidate(name, 0.52, 0.70, 0.41, List.of());
    }

    @Test
    void theThresholdsAreTheFrozenOnes() {
        assertThat(DecisionRule.MIN_GAIN).isEqualTo(0.05);
        assertThat(DecisionRule.MAX_PARAPHRASE_DROP).isEqualTo(0.02);
    }

    @Test
    void onlyPostgresQualifies() {
        DecisionRule.Decision d = DecisionRule.decide(BASE, strong("pg-hybrid"), weak("cluster-hybrid"));
        assertThat(d.verdict()).isEqualTo(DecisionRule.Verdict.PG);
        assertThat(d.pg().qualifies()).isTrue();
        assertThat(d.cluster().qualifies()).isFalse();
        assertThat(d.cluster().reasons()).anyMatch(r -> r.contains("exact-token"));
        assertThat(d.cluster().reasons()).anyMatch(r -> r.contains("mixed"));
    }

    @Test
    void onlyTheClusterQualifies() {
        DecisionRule.Decision d = DecisionRule.decide(BASE, weak("pg-hybrid"), strong("cluster-hybrid"));
        assertThat(d.verdict()).isEqualTo(DecisionRule.Verdict.CLUSTER);
    }

    @Test
    void whenBothQualifyPostgresWinsUnlessTheClusterClearlyLeadsOnMixed() {
        // cluster mixed 0.52 vs pg 0.50: edge 0.02 < 0.05 → PG (cheaper operational shape).
        DecisionRule.Decision close = DecisionRule.decide(BASE, strong("pg-hybrid"),
            candidate("cluster-hybrid", 0.62, 0.70, 0.52, List.of()));
        assertThat(close.verdict()).isEqualTo(DecisionRule.Verdict.PG);
        assertThat(close.rationale()).contains("cheaper");

        // cluster mixed 0.56 vs pg 0.50: edge 0.06 >= 0.05 → CLUSTER.
        DecisionRule.Decision clear = DecisionRule.decide(BASE, strong("pg-hybrid"),
            candidate("cluster-hybrid", 0.62, 0.70, 0.56, List.of()));
        assertThat(clear.verdict()).isEqualTo(DecisionRule.Verdict.CLUSTER);
    }

    @Test
    void whenNeitherQualifiesTheVerdictIsNone() {
        DecisionRule.Decision d = DecisionRule.decide(BASE, weak("pg-hybrid"), weak("cluster-hybrid"));
        assertThat(d.verdict()).isEqualTo(DecisionRule.Verdict.NONE);
        assertThat(d.rationale()).contains("neither");
    }

    @Test
    void anIsolationFailureDisqualifiesAnOtherwiseWinningCandidate() {
        ChunkBenchResult leaky = candidate("pg-hybrid", 0.80, 0.80, 0.80, List.of("LEAK pg-hybrid: query q returned c-x"));
        DecisionRule.Decision d = DecisionRule.decide(BASE, leaky, weak("cluster-hybrid"));
        assertThat(d.pg().qualifies()).isFalse();
        assertThat(d.pg().reasons()).anyMatch(r -> r.contains("isolation"));
        assertThat(d.verdict()).isEqualTo(DecisionRule.Verdict.NONE);
    }

    @Test
    void aParaphraseDropOfExactlyTwoHundredthsIsAllowedButMoreIsNot() {
        ChunkBenchResult edge = candidate("pg-hybrid", 0.60, 0.68, 0.50, List.of());
        ChunkBenchResult over = candidate("pg-hybrid", 0.60, 0.679, 0.50, List.of());

        assertThat(DecisionRule.decide(BASE, edge, weak("cluster-hybrid")).pg().qualifies()).isTrue();
        DecisionRule.Decision rejected = DecisionRule.decide(BASE, over, weak("cluster-hybrid"));
        assertThat(rejected.pg().qualifies()).isFalse();
        assertThat(rejected.pg().reasons()).anyMatch(r -> r.contains("paraphrase"));
    }

    @Test
    void aGainOfExactlyFiveHundredthsQualifiesButLessDoesNot() {
        ChunkBenchResult edge = candidate("pg-hybrid", 0.55, 0.70, 0.45, List.of());
        ChunkBenchResult under = candidate("pg-hybrid", 0.55, 0.70, 0.449, List.of());

        assertThat(DecisionRule.decide(BASE, edge, weak("cluster-hybrid")).pg().qualifies()).isTrue();
        assertThat(DecisionRule.decide(BASE, under, weak("cluster-hybrid")).pg().qualifies()).isFalse();
    }
}
