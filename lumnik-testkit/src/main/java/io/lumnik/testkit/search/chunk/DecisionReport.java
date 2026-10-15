package io.lumnik.testkit.search.chunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the #675 decision report as markdown: verdict first, per-class figures, caveats stated, never hidden. */
public final class DecisionReport {

    private DecisionReport() {
    }

    public static String render(DecisionRule.Decision decision, ChunkBenchResult baseline, ChunkBenchResult pg,
            ChunkBenchResult cluster, ChunkFixture.Manifest manifest) {
        StringBuilder md = new StringBuilder();
        md.append("# RAG hybrid retrieval — decision report (#675)\n\n");
        md.append("**Verdict: ").append(decision.verdict()).append("** — ").append(decision.rationale()).append(".\n\n");
        md.append("The rule that produced it is an experimental qualification rule (spec §5.3), fixed before any"
            + " measurement: a candidate qualifies with zero isolation failures, an nDCG@10 gain of at least ")
            .append(String.format(Locale.ROOT, "%.2f", DecisionRule.MIN_GAIN))
            .append(" on `exact-token` and on `mixed`, and a paraphrase drop of at most ")
            .append(String.format(Locale.ROOT, "%.2f", DecisionRule.MAX_PARAPHRASE_DROP))
            .append(". It decides whether a promotion design is worth writing; it is not a GA threshold.\n\n");

        md.append("## Relevance per query class (nDCG@10 / recall@10)\n\n");
        md.append("| Class | Queries | ").append(baseline.candidate()).append(" | ").append(pg.candidate()).append(" | ")
            .append(cluster.candidate()).append(" |\n|---|---|---|---|---|\n");
        for (QueryClass queryClass : List.of(QueryClass.EXACT_TOKEN, QueryClass.PARAPHRASE, QueryClass.MIXED)) {
            md.append("| ").append(queryClass.label()).append(" | ").append(baseline.of(queryClass).queries());
            for (ChunkBenchResult r : List.of(baseline, pg, cluster)) {
                md.append(" | ").append(cell(r.of(queryClass)));
            }
            md.append(" |\n");
        }
        md.append("| overall | ").append(baseline.overall().queries());
        for (ChunkBenchResult r : List.of(baseline, pg, cluster)) {
            md.append(" | ").append(cell(r.overall()));
        }
        md.append(" |\n\nThe overall row is never the only one read: it can hide a regression in one class.\n\n");

        md.append("## Qualification\n\n");
        appendAssessment(md, decision.pg());
        appendAssessment(md, decision.cluster());

        md.append("\n## Per-query nDCG@10\n\n");
        md.append("| Query | Class | ").append(baseline.candidate()).append(" | ").append(pg.candidate()).append(" | ")
            .append(cluster.candidate()).append(" |\n|---|---|---|---|---|\n");
        Map<String, ChunkBenchResult.QueryMetrics> pgById = byId(pg);
        Map<String, ChunkBenchResult.QueryMetrics> clusterById = byId(cluster);
        for (ChunkBenchResult.QueryMetrics base : baseline.perQuery()) {
            md.append("| ").append(base.id()).append(" | ").append(base.queryClass()).append(" | ")
                .append(String.format(Locale.ROOT, "%.3f", base.ndcgAt10())).append(" | ")
                .append(ndcgOf(pgById, base.id())).append(" | ").append(ndcgOf(clusterById, base.id())).append(" |\n");
        }

        md.append("\n## Isolation\n\n");
        for (ChunkBenchResult r : List.of(baseline, pg, cluster)) {
            md.append("- **").append(r.candidate()).append("**: ");
            if (r.isolationFailures().isEmpty()) {
                md.append("no failures\n");
            } else {
                md.append(r.isolationFailures().size()).append(" failure(s)\n");
                r.isolationFailures().forEach(f -> md.append("  - ").append(f).append('\n'));
            }
        }

        md.append("\n## Latency (local comparative signal)\n\n");
        md.append("| Candidate | p95 (ms) | Stable across passes |\n|---|---|---|\n");
        for (ChunkBenchResult r : List.of(baseline, pg, cluster)) {
            md.append("| ").append(r.candidate()).append(" | ").append(String.format(Locale.ROOT, "%.2f", r.p95LatencyMillis()))
                .append(" | ").append(r.stableAcrossPasses() ? "yes" : "NO").append(" |\n");
        }
        md.append("\nProtocol: one machine, both backends in local containers, single-threaded sequential queries, ")
            .append(baseline.warmupPasses()).append(" warm-up passes discarded then ").append(baseline.measuredPasses())
            .append(" measured passes over the whole scored query set, identical for every candidate. These numbers are")
            .append(" a local comparative signal: PostgreSQL-vs-OpenSearch absolute times are not a capacity claim and")
            .append(" must not be read as one. A corpus of this size also makes ANN behaviour irrelevant — nothing here")
            .append(" says anything about volumes of the #750 class.\n\n");

        md.append("## Corpus and frozen vectors\n\n");
        md.append("- Embedding model: `").append(manifest.model()).append("` (digest `").append(manifest.modelDigest())
            .append("`), Ollama ").append(manifest.ollamaVersion()).append(", ").append(manifest.dimension())
            .append(" dimensions, vectors `").append(manifest.vectorsSha256()).append("`, normalisation: ").append(manifest.normalisation()).append(", path: ")
            .append(manifest.embeddingPath()).append('\n');
        md.append("- Corpus schemaVersion ").append(manifest.schemaVersion()).append(", fingerprint `")
            .append(baseline.corpusFingerprint()).append("`\n\n");

        md.append("## Known limits\n\n");
        md.append("- The PostgreSQL lexical branch uses `ts_rank_cd`, which has no IDF, whereas OpenSearch BM25 weighs"
            + " terms by IDF; each backend's stock lexical analysis is compared on purpose (spec §5.1).\n");
        md.append("- A few dozen queries per class is a small sample; a borderline result is borderline, not a win.\n");
        md.append("- One embedding model; the claim is scoped to it.\n");
        md.append("- Parameters (RRF k=60, depth 50, tie-break) were fixed before measuring; none was tuned afterwards.\n");
        return md.toString();
    }

    private static Map<String, ChunkBenchResult.QueryMetrics> byId(ChunkBenchResult result) {
        Map<String, ChunkBenchResult.QueryMetrics> byId = new LinkedHashMap<>();
        result.perQuery().forEach(m -> byId.put(m.id(), m));
        return byId;
    }

    private static String ndcgOf(Map<String, ChunkBenchResult.QueryMetrics> byId, String id) {
        ChunkBenchResult.QueryMetrics m = byId.get(id);
        return m == null ? "n/a" : String.format(Locale.ROOT, "%.3f", m.ndcgAt10());
    }

    /**
     * Refuses results that were not measured on the fixture the report will cite: the manifest in the checkout is
     * attributed to every result, so each result must carry exactly its schema version, model digest and vectors checksum.
     */
    public static void requireSameFixture(ChunkFixture.Manifest manifest, List<ChunkBenchResult> results) {
        ChunkBenchResult.FixtureIdentity current = ChunkBenchResult.FixtureIdentity.of(manifest);
        for (ChunkBenchResult r : results) {
            ChunkBenchResult.FixtureIdentity measured = r.fixture();
            if (measured == null) {
                throw new IllegalStateException(r.candidate() + " carries no fixture identity");
            }
            if (measured.schemaVersion() != current.schemaVersion()) {
                throw new IllegalStateException(r.candidate() + " was measured on corpus schema "
                    + measured.schemaVersion() + ", the checkout is at " + current.schemaVersion());
            }
            if (!measured.modelDigest().equals(current.modelDigest())) {
                throw new IllegalStateException(r.candidate() + " was measured with model digest "
                    + measured.modelDigest() + ", the checkout's manifest says " + current.modelDigest());
            }
            if (!measured.vectorsSha256().equals(current.vectorsSha256())) {
                throw new IllegalStateException(r.candidate() + " was measured on vectors " + measured.vectorsSha256()
                    + ", the checkout's vectors.f32 is " + current.vectorsSha256());
            }
        }
    }

    private static String cell(ChunkBenchResult.ClassMetrics m) {
        return String.format(Locale.ROOT, "%.3f / %.3f", m.ndcgAt10(), m.recallAt10());
    }

    private static void appendAssessment(StringBuilder md, DecisionRule.Assessment a) {
        md.append("- **").append(a.candidate()).append("**: ").append(a.qualifies() ? "qualifies" : "does not qualify");
        if (!a.reasons().isEmpty()) {
            md.append(" — ").append(String.join("; ", a.reasons()));
        }
        md.append('\n');
    }
}
