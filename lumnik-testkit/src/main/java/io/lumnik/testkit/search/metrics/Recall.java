package io.lumnik.testkit.search.metrics;

import java.util.List;

/**
 * Recall@k over grade &gt;= 2 ("relevant"). {@code totalRelevantCount} is the number of grade
 * &gt;= 2 judgments for the query across the whole corpus, independent of k.
 */
public final class Recall {

    /** Grade at or above which a judgment counts as "relevant" for recall@k. */
    public static final int RELEVANT_THRESHOLD = 2;

    private Recall() {
    }

    public static double at(int k, List<Integer> retrievedGradesInRankOrder, int totalRelevantCount) {
        if (totalRelevantCount == 0) {
            return 0.0;
        }
        int limit = Math.min(k, retrievedGradesInRankOrder.size());
        long retrievedRelevant = retrievedGradesInRankOrder.subList(0, limit).stream()
                .filter(grade -> grade >= RELEVANT_THRESHOLD)
                .count();
        return (double) retrievedRelevant / totalRelevantCount;
    }
}
