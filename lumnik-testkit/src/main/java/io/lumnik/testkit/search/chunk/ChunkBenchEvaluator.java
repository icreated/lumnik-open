package io.lumnik.testkit.search.chunk;

import io.lumnik.testkit.search.chunk.ChunkRetrievalCandidate.CandidateQuery;
import io.lumnik.testkit.search.metrics.LatencyStats;
import io.lumnik.testkit.search.metrics.Ndcg;
import io.lumnik.testkit.search.metrics.Recall;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one {@link ChunkRetrievalCandidate} through the #675 protocol: warm-up passes (discarded), then measured passes
 * over every scored query; relevance comes from the last measured pass, p95 from all measured samples. Isolation
 * queries are asserted once, outside the timed passes: a forbidden chunk anywhere in the returned list is a LEAK, a
 * control that does not come first is a CONTROL failure.
 */
public final class ChunkBenchEvaluator {

    public static final int K = 10;

    /** Latency protocol. {@link #FROZEN} is the one the spec fixes; tests may use cheaper ones. */
    public record Protocol(int warmupPasses, int measuredPasses) {
        public static final Protocol FROZEN = new Protocol(20, 5);

        /** Cheap protocol for ordinary builds: relevance is deterministic, only latency needs the frozen passes. */
        public static final Protocol QUICK = new Protocol(1, 2);

        /** FROZEN under {@code -Dlumnik.chunkbench.frozen=true} (a measurement run), QUICK in every normal build. */
        public static Protocol forRun() {
            return Boolean.getBoolean("lumnik.chunkbench.frozen") ? FROZEN : QUICK;
        }

        public Protocol {
            if (warmupPasses < 0 || measuredPasses < 1) {
                throw new IllegalArgumentException("a protocol needs >= 0 warm-up and >= 1 measured passes");
            }
        }
    }

    private ChunkBenchEvaluator() {
    }

    public static ChunkBenchResult run(ChunkRetrievalCandidate candidate, ChunkCorpus corpus,
            Map<String, float[]> vectors, String corpusFingerprint, ChunkBenchResult.FixtureIdentity fixture,
            Protocol protocol) throws Exception {
        List<CorpusChunkQuery> scored = corpus.queries().stream().filter(q -> q.queryClass().scored()).toList();
        List<CorpusChunkQuery> isolation = corpus.queries().stream().filter(q -> !q.queryClass().scored()).toList();
        Set<String> knownChunks = new HashSet<>();
        corpus.chunks().forEach(c -> knownChunks.add(c.id()));

        candidate.load(corpus, vectors);

        for (int pass = 0; pass < protocol.warmupPasses(); pass++) {
            for (CorpusChunkQuery q : scored) {
                candidate.retrieve(toCandidateQuery(q, vectors));
            }
        }

        List<Double> latencies = new ArrayList<>();
        Map<String, List<String>> last = null;
        boolean stable = true;
        for (int pass = 0; pass < protocol.measuredPasses(); pass++) {
            Map<String, List<String>> results = new LinkedHashMap<>();
            for (CorpusChunkQuery q : scored) {
                long start = System.nanoTime();
                List<String> ids = candidate.retrieve(toCandidateQuery(q, vectors));
                latencies.add((System.nanoTime() - start) / 1_000_000.0);
                results.put(q.id(), checked(candidate, q, ids, knownChunks));
            }
            if (last != null && !last.equals(results)) {
                stable = false;
            }
            last = results;
        }

        Map<QueryClass, List<double[]>> byClass = new EnumMap<>(QueryClass.class);
        List<double[]> all = new ArrayList<>();
        List<ChunkBenchResult.QueryMetrics> perQuery = new ArrayList<>();
        for (CorpusChunkQuery q : scored) {
            double[] metrics = metrics(q, last.get(q.id()));
            byClass.computeIfAbsent(q.queryClass(), k -> new ArrayList<>()).add(metrics);
            all.add(metrics);
            perQuery.add(new ChunkBenchResult.QueryMetrics(q.id(), q.queryClass().label(), metrics[0], metrics[1]));
        }
        Map<String, ChunkBenchResult.ClassMetrics> perClass = new LinkedHashMap<>();
        for (QueryClass queryClass : List.of(QueryClass.EXACT_TOKEN, QueryClass.PARAPHRASE, QueryClass.MIXED)) {
            perClass.put(queryClass.label(), aggregate(byClass.getOrDefault(queryClass, List.of())));
        }

        List<String> failures = new ArrayList<>();
        for (CorpusChunkQuery q : isolation) {
            List<String> ids = checked(candidate, q, candidate.retrieve(toCandidateQuery(q, vectors)), knownChunks);
            for (String forbidden : q.mustBeAbsent()) {
                if (ids.contains(forbidden)) {
                    failures.add("LEAK " + candidate.name() + ": query " + q.id() + " returned forbidden chunk " + forbidden);
                }
            }
            if (q.mustRankFirst() != null && (ids.isEmpty() || !ids.get(0).equals(q.mustRankFirst()))) {
                failures.add("CONTROL " + candidate.name() + ": query " + q.id() + " ranked "
                    + (ids.isEmpty() ? "nothing" : ids.get(0)) + " first, expected " + q.mustRankFirst());
            }
        }

        return new ChunkBenchResult(candidate.name(), protocol.warmupPasses(), protocol.measuredPasses(),
            corpusFingerprint, fixture, perClass, aggregate(all), perQuery, LatencyStats.p95(latencies), stable, failures);
    }

    private static CandidateQuery toCandidateQuery(CorpusChunkQuery q, Map<String, float[]> vectors) {
        float[] vector = vectors.get(q.id());
        if (vector == null) {
            throw new IllegalStateException("no frozen vector for query " + q.id());
        }
        return new CandidateQuery(q.tenantId(), q.corpus(), q.scopeTags(), q.text(), vector, K);
    }

    private static List<String> checked(ChunkRetrievalCandidate candidate, CorpusChunkQuery q, List<String> ids,
            Set<String> knownChunks) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!knownChunks.contains(id)) {
                throw new IllegalStateException("candidate " + candidate.name() + " returned an unknown chunk id \""
                    + id + "\" for query " + q.id() + "; it must return corpus chunk ids");
            }
            if (!seen.add(id)) {
                throw new IllegalStateException("candidate " + candidate.name() + " returned chunk " + id
                    + " twice for query " + q.id());
            }
        }
        return ids;
    }

    private static double[] metrics(CorpusChunkQuery q, List<String> ids) {
        List<Integer> retrievedGrades = ids.stream().map(id -> q.judgments().getOrDefault(id, 0)).toList();
        List<Integer> judgedGrades = new ArrayList<>(q.judgments().values());
        int totalRelevant = (int) judgedGrades.stream().filter(g -> g >= Recall.RELEVANT_THRESHOLD).count();
        return new double[] {Ndcg.at(K, retrievedGrades, judgedGrades), Recall.at(K, retrievedGrades, totalRelevant)};
    }

    private static ChunkBenchResult.ClassMetrics aggregate(List<double[]> perQuery) {
        if (perQuery.isEmpty()) {
            return new ChunkBenchResult.ClassMetrics(0, 0.0, 0.0);
        }
        double ndcg = perQuery.stream().mapToDouble(m -> m[0]).average().orElseThrow();
        double recall = perQuery.stream().mapToDouble(m -> m[1]).average().orElseThrow();
        return new ChunkBenchResult.ClassMetrics(perQuery.size(), ndcg, recall);
    }
}
