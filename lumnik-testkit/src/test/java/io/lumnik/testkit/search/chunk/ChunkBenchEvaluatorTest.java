package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.testkit.search.chunk.ChunkRetrievalCandidate.CandidateQuery;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkBenchEvaluatorTest {

    private static final String YAML = """
        schemaVersion: 1
        seed: 675
        chunks:
          - {id: c-a, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "alpha invoice"}
          - {id: c-b, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "beta"}
          - {id: c-p, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "gamma"}
          - {id: c-x, tenantId: 2, corpus: orders, tags: [ventes], type: connector, text: "alpha invoice"}
        queries:
          - {id: q-exact, class: exact-token, language: en, tenantId: 1, text: "alpha", judgments: {c-a: 3, c-b: 1}}
          - {id: q-para, class: paraphrase, language: en, tenantId: 1, text: "something", judgments: {c-p: 3}}
          - {id: q-denied, class: isolation, language: en, tenantId: 1, text: "alpha", mustBeAbsent: [c-x]}
          - {id: q-control, class: isolation, language: en, tenantId: 2, text: "alpha", mustRankFirst: c-x}
        """;

    private static final ChunkBenchResult.FixtureIdentity ID = new ChunkBenchResult.FixtureIdentity(3, "digest", "sha256:v");

    private static final ChunkBenchEvaluator.Protocol FAST = new ChunkBenchEvaluator.Protocol(1, 2);

    private static final class Stub implements ChunkRetrievalCandidate {
        private final BiFunction<ChunkCorpus, CandidateQuery, List<String>> behaviour;
        private ChunkCorpus corpus;
        int loads;
        int retrieves;

        Stub(BiFunction<ChunkCorpus, CandidateQuery, List<String>> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public void load(ChunkCorpus corpus, Map<String, float[]> vectors) {
            this.corpus = corpus;
            loads++;
        }

        @Override
        public List<String> retrieve(CandidateQuery query) {
            retrieves++;
            return behaviour.apply(corpus, query);
        }
    }

    private static ChunkCorpus corpus() {
        return ChunkCorpusLoader.load(YAML);
    }

    private static Map<String, float[]> vectors(ChunkCorpus corpus) {
        Map<String, float[]> vectors = new HashMap<>();
        ChunkFixture.orderedIds(corpus).forEach(id -> vectors.put(id, new float[] {0f}));
        return vectors;
    }

    /** Knows the judged ranking of each (tenant, text) — what a perfect retriever would answer. */
    private static List<String> oracle(ChunkCorpus corpus, CandidateQuery q) {
        for (CorpusChunkQuery cq : corpus.queries()) {
            if (cq.tenantId() != q.tenantId() || !cq.text().equals(q.text())) {
                continue;
            }
            if (cq.mustRankFirst() != null) {
                return List.of(cq.mustRankFirst());
            }
            return cq.judgments().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        }
        return List.of();
    }

    private static ChunkBenchResult run(Stub stub) throws Exception {
        ChunkCorpus c = corpus();
        return ChunkBenchEvaluator.run(stub, c, vectors(c), "sha256:test", ID, FAST);
    }

    @Test
    void aPerfectRetrieverScoresOneAndLeaksNothing() throws Exception {
        ChunkBenchResult result = run(new Stub(ChunkBenchEvaluatorTest::oracle));

        assertThat(result.perClass()).containsOnlyKeys("exact-token", "paraphrase", "mixed");
        assertThat(result.of(QueryClass.EXACT_TOKEN).ndcgAt10()).isEqualTo(1.0);
        assertThat(result.of(QueryClass.EXACT_TOKEN).recallAt10()).isEqualTo(1.0);
        assertThat(result.of(QueryClass.PARAPHRASE).ndcgAt10()).isEqualTo(1.0);
        assertThat(result.of(QueryClass.MIXED).queries()).isZero();
        assertThat(result.overall().queries()).isEqualTo(2);
        assertThat(result.perQuery()).extracting(ChunkBenchResult.QueryMetrics::id).containsExactly("q-exact", "q-para");
        assertThat(result.perQuery().get(0).queryClass()).isEqualTo("exact-token");
        assertThat(result.perQuery().get(0).ndcgAt10()).isEqualTo(1.0);
        assertThat(result.isolationFailures()).isEmpty();
        assertThat(result.stableAcrossPasses()).isTrue();
        assertThat(result.p95LatencyMillis()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.corpusFingerprint()).isEqualTo("sha256:test");
        assertThat(result.fixture()).isEqualTo(ID);
        assertThat(result.candidate()).isEqualTo("stub");
    }

    @Test
    void anEmptyRetrieverScoresZeroAndFailsTheControl() throws Exception {
        ChunkBenchResult result = run(new Stub((c, q) -> List.of()));

        assertThat(result.overall().ndcgAt10()).isZero();
        assertThat(result.isolationFailures()).singleElement().asString().contains("CONTROL").contains("q-control");
    }

    @Test
    void aLeakyRetrieverIsCaughtByBothTheLeakAndTheControlAssertion() throws Exception {
        // Ignores tenant: returns every chunk, sorted by id — c-a comes first even for tenant 2.
        ChunkBenchResult result = run(new Stub((c, q) -> c.chunks().stream().map(CorpusChunk::id).sorted().toList()));

        assertThat(result.isolationFailures()).hasSize(2);
        assertThat(result.isolationFailures()).anyMatch(f -> f.startsWith("LEAK") && f.contains("c-x"));
        assertThat(result.isolationFailures()).anyMatch(f -> f.startsWith("CONTROL") && f.contains("q-control"));
    }

    @Test
    void classesAreReportedSeparatelyAndTheOverallDoesNotHideARegression() throws Exception {
        // Perfect on exact-token, blind on paraphrase.
        ChunkBenchResult result = run(new Stub((c, q) -> q.text().equals("something") ? List.of() : oracle(c, q)));

        assertThat(result.of(QueryClass.EXACT_TOKEN).ndcgAt10()).isEqualTo(1.0);
        assertThat(result.of(QueryClass.PARAPHRASE).ndcgAt10()).isZero();
        assertThat(result.overall().ndcgAt10()).isEqualTo(0.5);
    }

    @Test
    void aDuplicateChunkIdIsRejected() {
        Stub stub = new Stub((c, q) -> List.of("c-a", "c-a"));
        assertThatThrownBy(() -> run(stub)).isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
    }

    @Test
    void anIdThatIsNotInTheCorpusIsRejected() {
        Stub stub = new Stub((c, q) -> List.of("3f2c-some-row-uuid"));
        assertThatThrownBy(() -> run(stub)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown chunk id");
    }

    @Test
    void aNondeterministicRetrieverIsFlaggedUnstable() throws Exception {
        List<Integer> calls = new ArrayList<>();
        // Period 3, not 2: a pass makes 2 scored calls, so a period-2 alternation would repeat identically each pass.
        Stub stub = new Stub((c, q) -> {
            calls.add(1);
            return calls.size() % 3 == 0 ? List.of("c-a", "c-b") : List.of("c-b", "c-a");
        });

        assertThat(run(stub).stableAcrossPasses()).isFalse();
    }

    @Test
    void theProtocolLoadsOnceAndRunsWarmupThenMeasuredPassesOverTheScoredQueriesOnly() throws Exception {
        Stub stub = new Stub(ChunkBenchEvaluatorTest::oracle);
        ChunkCorpus c = corpus();

        ChunkBenchEvaluator.run(stub, c, vectors(c), "sha256:test", ID, new ChunkBenchEvaluator.Protocol(2, 3));

        assertThat(stub.loads).isEqualTo(1);
        // 2 scored queries × (2 warm-up + 3 measured) passes + 2 isolation queries once.
        assertThat(stub.retrieves).isEqualTo(2 * 5 + 2);
    }

    @Test
    void theFrozenProtocolIsTwentyWarmupAndFiveMeasuredPasses() {
        assertThat(ChunkBenchEvaluator.Protocol.FROZEN).isEqualTo(new ChunkBenchEvaluator.Protocol(20, 5));
    }

    @Test
    void theRunProtocolIsFrozenOnlyWhenAskedFor() {
        String key = "lumnik.chunkbench.frozen";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertThat(ChunkBenchEvaluator.Protocol.forRun()).isEqualTo(ChunkBenchEvaluator.Protocol.QUICK);
            System.setProperty(key, "true");
            assertThat(ChunkBenchEvaluator.Protocol.forRun()).isEqualTo(ChunkBenchEvaluator.Protocol.FROZEN);
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void aProtocolWithoutAMeasuredPassIsRejected() {
        assertThatThrownBy(() -> new ChunkBenchEvaluator.Protocol(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResultSurvivesAJsonRoundTrip(@TempDir Path dir) throws Exception {
        ChunkBenchResult result = run(new Stub(ChunkBenchEvaluatorTest::oracle));
        Path file = dir.resolve("out/stub.json");

        result.write(file);

        assertThat(ChunkBenchResult.read(file)).isEqualTo(result);
        assertThat(ChunkBenchResult.readIfPresent(dir.resolve("missing.json"))).isEmpty();
    }
}
