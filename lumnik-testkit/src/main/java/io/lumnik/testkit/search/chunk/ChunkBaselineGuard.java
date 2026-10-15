package io.lumnik.testkit.search.chunk;

import io.lumnik.testkit.search.baseline.BaselineComparator;
import io.lumnik.testkit.search.baseline.BaselineRegressionException;
import java.util.Optional;

/**
 * Gates a candidate run against a committed baseline, class by class — a global mean is never enough, because it can
 * hide a paraphrase regression behind an exact-token gain. Never establishes a baseline by itself.
 */
public final class ChunkBaselineGuard {

    private static final double TOLERANCE = 0.01;

    private ChunkBaselineGuard() {
    }

    public static void assertNoRegression(Optional<ChunkBenchResult> stored, ChunkBenchResult current) {
        if (!current.isolationFailures().isEmpty()) {
            throw new BaselineRegressionException("isolation failures: " + current.isolationFailures());
        }
        if (stored.isEmpty()) {
            throw new BaselineRegressionException("no baseline found for " + current.candidate()
                + "; establish one with " + BaselineComparator.UPDATE_COMMAND);
        }
        ChunkBenchResult baseline = stored.get();
        if (!baseline.candidate().equals(current.candidate())) {
            throw new BaselineRegressionException("baseline is for " + baseline.candidate() + ", not "
                + current.candidate());
        }
        if (!baseline.corpusFingerprint().equals(current.corpusFingerprint())) {
            throw new BaselineRegressionException("baseline is stale: corpus fingerprint changed from "
                + baseline.corpusFingerprint() + " to " + current.corpusFingerprint()
                + "; a changed corpus is a new version — establish a new baseline with "
                + BaselineComparator.UPDATE_COMMAND);
        }
        if (!baseline.fixture().equals(current.fixture())) {
            throw new BaselineRegressionException("baseline is stale: it was measured on fixture " + baseline.fixture()
                + ", this run used " + current.fixture() + "; other vectors or another model are a new corpus version —"
                + " establish a new baseline with " + BaselineComparator.UPDATE_COMMAND);
        }
        baseline.perClass().forEach((label, base) -> {
            ChunkBenchResult.ClassMetrics now = current.perClass().get(label);
            requireNoDrop(label + ".ndcgAt10", base.ndcgAt10(), now.ndcgAt10());
            requireNoDrop(label + ".recallAt10", base.recallAt10(), now.recallAt10());
        });
    }

    private static void requireNoDrop(String metric, double baseline, double current) {
        double drop = baseline - current;
        if (drop > TOLERANCE) {
            throw new BaselineRegressionException(metric + " regressed from " + baseline + " to " + current
                + " (drop " + drop + " exceeds tolerance " + TOLERANCE + ")");
        }
    }
}
