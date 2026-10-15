package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionReportTest {

    private static final ChunkFixture.Manifest MANIFEST = new ChunkFixture.Manifest(1, "nomic-embed-text",
        "0a109f422b47", "0.30.6", 768, "none", "ollama /api/embed (direct)", "2026-10-02T00:00:00Z", "cmd",
        "sha256:t", "sha256:v", List.of());

    @Test
    void theReportStatesTheVerdictEveryClassAndTheCaveats() {
        ChunkBenchResult base = ChunkBaselineGuardTest.result("sha256:a", 0.50, 0.70, 0.40, List.of());
        ChunkBenchResult pg = ChunkBaselineGuardTest.result("sha256:a", 0.60, 0.70, 0.50, List.of());
        ChunkBenchResult cluster = ChunkBaselineGuardTest.result("sha256:a", 0.52, 0.70, 0.41,
            List.of("LEAK cluster-hybrid: query q returned c-x"));
        DecisionRule.Decision decision = DecisionRule.decide(base, pg, cluster);

        String md = DecisionReport.render(decision, base, pg, cluster, MANIFEST);

        assertThat(md).contains("Verdict: PG");
        assertThat(md).contains("exact-token").contains("paraphrase").contains("mixed").contains("overall");
        assertThat(md).contains("0.600").contains("0.500");
        assertThat(md).contains("LEAK cluster-hybrid");
        assertThat(md).contains("not a capacity").contains("20 warm-up").contains("5 measured");
        assertThat(md).contains("no IDF");
        assertThat(md).contains("nomic-embed-text").contains("0.30.6").contains("none");
        assertThat(md).contains("experimental qualification rule");
    }

    @Test
    void theReportListsEveryQueryWithItsScorePerCandidate() {
        ChunkBenchResult base = withQueries(ChunkBaselineGuardTest.result("sha256:a", 0.50, 0.70, 0.40, List.of()), 0.25);
        ChunkBenchResult pg = withQueries(ChunkBaselineGuardTest.result("sha256:a", 0.60, 0.70, 0.50, List.of()), 0.75);
        ChunkBenchResult cluster = ChunkBaselineGuardTest.result("sha256:a", 0.52, 0.70, 0.41, List.of());

        String md = DecisionReport.render(DecisionRule.decide(base, pg, cluster), base, pg, cluster, MANIFEST);

        assertThat(md).contains("| q-e01 | exact-token | 0.250 | 0.750 | n/a |");
    }

    private static ChunkBenchResult withQueries(ChunkBenchResult r, double ndcg) {
        return new ChunkBenchResult(r.candidate(), r.warmupPasses(), r.measuredPasses(), r.corpusFingerprint(), r.fixture(),
            r.perClass(), r.overall(), List.of(new ChunkBenchResult.QueryMetrics("q-e01", "exact-token", ndcg, ndcg)),
            r.p95LatencyMillis(), r.stableAcrossPasses(), r.isolationFailures());
    }

    private static ChunkFixture.Manifest manifest(int schema, String digest, String vectors) {
        return new ChunkFixture.Manifest(schema, "nomic-embed-text", digest, "0.30.6", 768, "none", "path",
            "2026-10-02T00:00:00Z", "cmd", "sha256:t", vectors, List.of());
    }

    @Test
    void resultsMeasuredOnTheCurrentFixtureAreAccepted() {
        ChunkBenchResult r = ChunkBaselineGuardTest.result("sha256:a", 0.5, 0.7, 0.4, List.of());

        DecisionReport.requireSameFixture(manifest(3, "digest", "sha256:v"), List.of(r, r, r));
    }

    @Test
    void resultsMeasuredOnAnotherFixtureAreRefusedWhicheverPartDiffers() {
        ChunkBenchResult r = ChunkBaselineGuardTest.result("sha256:a", 0.5, 0.7, 0.4, List.of());

        assertThatThrownBy(() -> DecisionReport.requireSameFixture(manifest(3, "digest", "sha256:changed"), List.of(r)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("vectors");
        assertThatThrownBy(() -> DecisionReport.requireSameFixture(manifest(3, "new-digest", "sha256:v"), List.of(r)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("model");
        assertThatThrownBy(() -> DecisionReport.requireSameFixture(manifest(4, "digest", "sha256:v"), List.of(r)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema");
    }

    @Test
    void aNoneVerdictIsRenderedAsSuch() {
        ChunkBenchResult base = ChunkBaselineGuardTest.result("sha256:a", 0.50, 0.70, 0.40, List.of());
        ChunkBenchResult flat = ChunkBaselineGuardTest.result("sha256:a", 0.50, 0.70, 0.40, List.of());

        String md = DecisionReport.render(DecisionRule.decide(base, flat, flat), base, flat, flat, MANIFEST);

        assertThat(md).contains("Verdict: NONE").contains("neither");
    }
}
