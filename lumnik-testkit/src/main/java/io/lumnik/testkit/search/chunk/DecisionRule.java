package io.lumnik.testkit.search.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The #675 qualification rule (spec §5.3), frozen before any measurement. An EXPERIMENTAL rule that decides whether a
 * candidate deserves a promotion design — not a GA threshold, and not a claim of statistical significance on a small
 * corpus.
 */
public final class DecisionRule {

    public static final double MIN_GAIN = 0.05;
    public static final double MAX_PARAPHRASE_DROP = 0.02;
    private static final double EPSILON = 1e-9;

    public enum Verdict { NONE, PG, CLUSTER }

    public record Assessment(String candidate, boolean qualifies, List<String> reasons) {
    }

    public record Decision(Verdict verdict, Assessment pg, Assessment cluster, String rationale) {
    }

    private DecisionRule() {
    }

    public static Decision decide(ChunkBenchResult baseline, ChunkBenchResult pg, ChunkBenchResult cluster) {
        Assessment pgAssessment = assess(baseline, pg);
        Assessment clusterAssessment = assess(baseline, cluster);
        if (pgAssessment.qualifies() && clusterAssessment.qualifies()) {
            double edge = mixed(cluster) - mixed(pg);
            if (edge >= MIN_GAIN - EPSILON) {
                return new Decision(Verdict.CLUSTER, pgAssessment, clusterAssessment, String.format(Locale.ROOT,
                    "both qualify; the cluster beats PostgreSQL by %.3f on mixed (>= %.2f)", edge, MIN_GAIN));
            }
            return new Decision(Verdict.PG, pgAssessment, clusterAssessment, String.format(Locale.ROOT,
                "both qualify; PostgreSQL wins — the cluster's edge on mixed is %.3f (< %.2f) and PostgreSQL is the"
                    + " cheaper operational shape", edge, MIN_GAIN));
        }
        if (pgAssessment.qualifies()) {
            return new Decision(Verdict.PG, pgAssessment, clusterAssessment, "only PostgreSQL qualifies");
        }
        if (clusterAssessment.qualifies()) {
            return new Decision(Verdict.CLUSTER, pgAssessment, clusterAssessment, "only the cluster qualifies");
        }
        return new Decision(Verdict.NONE, pgAssessment, clusterAssessment, "neither candidate qualifies");
    }

    static Assessment assess(ChunkBenchResult baseline, ChunkBenchResult candidate) {
        List<String> reasons = new ArrayList<>();
        if (!candidate.isolationFailures().isEmpty()) {
            reasons.add("isolation failures: " + candidate.isolationFailures().size());
        }
        requireGain(reasons, "exact-token", ndcg(candidate, QueryClass.EXACT_TOKEN) - ndcg(baseline, QueryClass.EXACT_TOKEN));
        requireGain(reasons, "mixed", ndcg(candidate, QueryClass.MIXED) - ndcg(baseline, QueryClass.MIXED));
        double paraphraseDrop = ndcg(baseline, QueryClass.PARAPHRASE) - ndcg(candidate, QueryClass.PARAPHRASE);
        if (paraphraseDrop > MAX_PARAPHRASE_DROP + EPSILON) {
            reasons.add(String.format(Locale.ROOT, "paraphrase nDCG@10 drops by %.3f (> %.2f)", paraphraseDrop,
                MAX_PARAPHRASE_DROP));
        }
        return new Assessment(candidate.candidate(), reasons.isEmpty(), List.copyOf(reasons));
    }

    private static void requireGain(List<String> reasons, String label, double gain) {
        if (gain < MIN_GAIN - EPSILON) {
            reasons.add(String.format(Locale.ROOT, "%s nDCG@10 gain %.3f is below %.2f", label, gain, MIN_GAIN));
        }
    }

    private static double ndcg(ChunkBenchResult result, QueryClass queryClass) {
        return result.of(queryClass).ndcgAt10();
    }

    private static double mixed(ChunkBenchResult result) {
        return ndcg(result, QueryClass.MIXED);
    }
}
