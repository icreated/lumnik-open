package io.lumnik.testkit.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.testkit.search.baseline.BaselineComparator;
import io.lumnik.testkit.search.baseline.BaselineIO;
import io.lumnik.testkit.search.baseline.BaselineSnapshot;
import io.lumnik.testkit.search.corpus.CorpusLoader;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import io.lumnik.testkit.search.metrics.LatencyStats;
import io.lumnik.testkit.search.reference.ReferenceIndexedSearchProvider;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the #704 harness works: loads the real, committed corpus, runs it through
 * the deliberately naive {@link ReferenceIndexedSearchProvider}, and gates the result against a
 * committed baseline. This is the second provider the generic harness is proven against — the
 * reference implementation's own numbers are not a product target.
 */
class ReferenceProviderEndToEndTest {

    private static final String CORPUS_RESOURCE = "search-corpus/v1/direct-search.yaml";
    private static final Path BASELINE_PATH =
            Path.of("src/test/resources/search-baselines/reference.json");
    private static final Path CANDIDATE_PATH =
            Path.of("target/search-baselines/reference.json");
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 10;

    @Test
    void referenceProviderResultsMatchTheCommittedBaseline() {
        String rawYaml = CorpusLoader.readResourceRaw(CORPUS_RESOURCE);
        SearchCorpus corpus = CorpusLoader.load(new StringReader(rawYaml));
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        // Sanity check: the naive reference provider must find *something* relevant somewhere in
        // the corpus — an all-zero run would mean the harness itself is broken. Individual hard
        // cases (transliteration, accents) are expected to score 0 and are covered separately by
        // ReferenceProviderKnownCasesTest.
        List<CorpusEvaluator.QueryResult> firstRun = CorpusEvaluator.evaluate(corpus, provider);
        assertThat(firstRun).isNotEmpty();
        assertThat(firstRun).anyMatch(result -> result.ndcgAt10() > 0.0);

        List<Double> allLatencies = new ArrayList<>();
        List<Double> allNdcgs = new ArrayList<>();
        List<Double> allRecalls = new ArrayList<>();
        for (int run = 0; run < WARMUP_RUNS + MEASURED_RUNS; run++) {
            List<CorpusEvaluator.QueryResult> results = CorpusEvaluator.evaluate(corpus, provider);
            if (run < WARMUP_RUNS) {
                continue;
            }
            for (CorpusEvaluator.QueryResult result : results) {
                allLatencies.add(result.latencyMillis());
                allNdcgs.add(result.ndcgAt10());
                allRecalls.add(result.recallAt10());
            }
        }

        double avgNdcg = allNdcgs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double avgRecall = allRecalls.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double p95Latency = LatencyStats.p95(allLatencies);

        BaselineSnapshot candidate = new BaselineSnapshot(1, BaselineIO.fingerprint(rawYaml),
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(avgNdcg, avgRecall, p95Latency));

        if (Boolean.getBoolean("lumnik.search.updateBaseline")) {
            BaselineIO.write(CANDIDATE_PATH, candidate);
            return;
        }

        Optional<BaselineSnapshot> stored = BaselineIO.read(BASELINE_PATH);
        BaselineComparator.assertNoRegression(stored, candidate);
    }
}
