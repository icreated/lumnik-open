package io.lumnik.testkit.search.baseline;

import java.util.Optional;

/**
 * Gates a candidate metric run against a committed {@link BaselineSnapshot}. Never establishes a
 * baseline on its own: a missing baseline is a hard failure naming the update command, so an
 * accidentally-deleted or never-committed baseline can't pass silently.
 */
public final class BaselineComparator {

    public static final String UPDATE_COMMAND = "-Dlumnik.search.updateBaseline=true";
    private static final double METRIC_REGRESSION_TOLERANCE = 0.01;

    private BaselineComparator() {
    }

    public static void assertNoRegression(Optional<BaselineSnapshot> storedBaseline, BaselineSnapshot candidate) {
        if (storedBaseline.isEmpty()) {
            throw new BaselineRegressionException("no baseline found for provider " + candidate.provider().name()
                    + "; establish one with " + UPDATE_COMMAND);
        }

        BaselineSnapshot baseline = storedBaseline.get();
        if (baseline.schemaVersion() != candidate.schemaVersion()) {
            throw new BaselineRegressionException("baseline schemaVersion " + baseline.schemaVersion()
                    + " does not match the candidate's schemaVersion " + candidate.schemaVersion()
                    + "; establish a new baseline with " + UPDATE_COMMAND);
        }
        if (!baseline.provider().name().equals(candidate.provider().name())) {
            throw new BaselineRegressionException("baseline is for provider " + baseline.provider().name()
                    + ", not the candidate's provider " + candidate.provider().name()
                    + "; wrong baseline file for this provider");
        }
        if (!baseline.corpusFingerprint().equals(candidate.corpusFingerprint())) {
            throw new BaselineRegressionException("baseline is stale: corpus fingerprint changed from "
                    + baseline.corpusFingerprint() + " to " + candidate.corpusFingerprint()
                    + "; recompute and review the diff before committing a new baseline");
        }

        requireNoDrop("ndcgAt10", baseline.metrics().ndcgAt10(), candidate.metrics().ndcgAt10());
        requireNoDrop("recallAt10", baseline.metrics().recallAt10(), candidate.metrics().recallAt10());
    }

    /**
     * Opt-in latency gate: not called by {@link #assertNoRegression}. p95 latency is noisy in a
     * shared CI environment, so real backends should gate it in their own IT/load test rather than
     * here; this exists so the reference provider's own tests can prove the mechanism.
     */
    public static void assertNoLatencyRegression(BaselineSnapshot baseline, BaselineSnapshot candidate,
            double relativeTolerance, double floorMillis) {
        double baselineP95 = baseline.metrics().p95LatencyMillis();
        double candidateP95 = candidate.metrics().p95LatencyMillis();
        double threshold = Math.max(baselineP95 * relativeTolerance, floorMillis);
        if (candidateP95 > threshold) {
            throw new BaselineRegressionException("p95LatencyMillis regressed from " + baselineP95 + " to "
                    + candidateP95 + " (exceeds threshold " + threshold + ")");
        }
    }

    private static void requireNoDrop(String metricName, double baselineValue, double candidateValue) {
        double drop = baselineValue - candidateValue;
        if (drop > METRIC_REGRESSION_TOLERANCE) {
            throw new BaselineRegressionException(metricName + " regressed from " + baselineValue + " to "
                    + candidateValue + " (drop " + drop + " exceeds tolerance " + METRIC_REGRESSION_TOLERANCE + ")");
        }
    }
}
