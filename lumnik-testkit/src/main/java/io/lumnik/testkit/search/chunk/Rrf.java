package io.lumnik.testkit.search.chunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion with the parameters frozen in the #675 spec: {@code score(d) = Σ 1/(K + rank_b(d))} over the
 * branches that returned {@code d}, ranks from 1; each branch contributes its first {@link #BRANCH_DEPTH}; a chunk in
 * several branches is one candidate whose contributions add. Total order: score desc, best single-branch rank asc,
 * chunk id asc (byte order).
 */
public final class Rrf {

    public static final int K = 60;
    public static final int BRANCH_DEPTH = 50;

    private Rrf() {
    }

    public static List<String> fuse(List<List<String>> rankedBranches, int topK) {
        Map<String, double[]> byId = new HashMap<>(); // id -> {score, best rank}
        for (List<String> branch : rankedBranches) {
            Set<String> seen = new HashSet<>();
            int limit = Math.min(BRANCH_DEPTH, branch.size());
            for (int i = 0; i < limit; i++) {
                String id = branch.get(i);
                if (!seen.add(id)) {
                    throw new IllegalArgumentException("a branch lists chunk " + id + " twice");
                }
                int rank = i + 1;
                double[] acc = byId.computeIfAbsent(id, k -> new double[] {0.0, Double.MAX_VALUE});
                acc[0] += 1.0 / (K + rank);
                acc[1] = Math.min(acc[1], rank);
            }
        }
        return byId.entrySet().stream()
            .sorted((x, y) -> {
                int byScore = Double.compare(y.getValue()[0], x.getValue()[0]);
                if (byScore != 0) {
                    return byScore;
                }
                int byBestRank = Double.compare(x.getValue()[1], y.getValue()[1]);
                return byBestRank != 0 ? byBestRank : x.getKey().compareTo(y.getKey());
            })
            .limit(topK)
            .map(Map.Entry::getKey)
            .toList();
    }
}
