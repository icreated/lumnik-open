package io.lumnik.testkit.search.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Graded nDCG@k. Gain is {@code 2^relevance - 1}; IDCG is computed from the query's full set of
 * judged grades (not just the ones retrieved), sorted descending and truncated to k.
 */
public final class Ndcg {

    private Ndcg() {
    }

    public static double at(int k, List<Integer> retrievedGradesInRankOrder, List<Integer> allJudgedGrades) {
        double dcg = discountedGain(retrievedGradesInRankOrder, k);

        List<Integer> ideal = new ArrayList<>(allJudgedGrades);
        ideal.sort(Comparator.reverseOrder());
        double idcg = discountedGain(ideal, k);

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double discountedGain(List<Integer> gradesInOrder, int k) {
        double sum = 0.0;
        int limit = Math.min(k, gradesInOrder.size());
        for (int i = 0; i < limit; i++) {
            double gain = Math.pow(2, gradesInOrder.get(i)) - 1;
            double discount = Math.log(i + 2) / Math.log(2);
            sum += gain / discount;
        }
        return sum;
    }
}
