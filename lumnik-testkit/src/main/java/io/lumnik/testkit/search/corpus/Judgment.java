package io.lumnik.testkit.search.corpus;

/** {@code relevance} is a graded score in {@code [MIN_RELEVANCE, MAX_RELEVANCE]}. */
public record Judgment(String hit, int relevance) {

    /** Lowest valid relevance grade (inclusive) — irrelevant. */
    public static final int MIN_RELEVANCE = 0;

    /** Highest valid relevance grade (inclusive) — "résultat idéal". */
    public static final int MAX_RELEVANCE = 3;

    /** Grade at or above which a judgment counts as "positive"; a query needs at least one. */
    public static final int POSITIVE_RELEVANCE_THRESHOLD = 1;
}
