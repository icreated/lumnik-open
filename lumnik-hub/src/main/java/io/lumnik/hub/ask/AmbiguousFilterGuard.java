package io.lumnik.hub.ask;

import io.lumnik.hub.sql.SqlFailureText;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The honest answer to a filter that names more than one row.
 *
 * <p>A generated query routinely filters through a scalar subquery —
 * {@code WHERE c_bpartner_id = (SELECT c_bpartner_id FROM … WHERE name = 'X')}. The model is not
 * being careless: nothing in the schema card says a name is unique, so it assumes what a name
 * usually is. When two rows carry it, PostgreSQL refuses the whole query with SQLSTATE 21000,
 * <i>more than one row returned by a subquery used as an expression</i>.
 *
 * <p>Three answers were possible and two of them are bad. Rewriting {@code =} into {@code IN}
 * would silently decide that the user meant both — a business call the system would be making on
 * their behalf, which is the confident-wrong this whole line exists to refuse. A bare "I couldn't
 * run that" is honest but leaves them stuck. So: <b>disclose the ambiguity and hand over the means
 * to resolve it</b> — the ids that matched, so the next question can name one.
 *
 * <p>How the failure is READ belongs to {@link SqlFailureText}: one bounded cause walk, one rule
 * about which database messages may travel. The SQL side of the disclosure — finding the subquery to re-run — belongs to
 * {@code SqlFilters}, this package's home for lexical extraction.
 *
 * <p>Everything here is pure and fail-open: what cannot be built falls back to the plain refusal,
 * so an unrecognised shape costs a nicety, never a guarantee.
 */
public final class AmbiguousFilterGuard {

    /** {@code cardinality_violation} — what PostgreSQL raises for a multi-row scalar subquery. */
    static final String SQLSTATE_CARDINALITY = "21000";

    /** How many matches to name before summarising the rest; a duplicate set can be long. */
    static final int MAX_LISTED = 10;

    private AmbiguousFilterGuard() {
    }

    /** True when this failure is a scalar subquery that matched several rows. */
    public static boolean isAmbiguity(Throwable failure) {
        return SqlFailureText.causeChain(failure).stream().anyMatch(
            t -> t instanceof SQLException sql && SQLSTATE_CARDINALITY.equals(sql.getSQLState()));
    }

    /**
     * The disclosure: what the filter matched, and the gesture that resolves it.
     *
     * <p>{@code values} is the probe's rows, in order; the listing is DEDUPLICATED because the
     * same id twice is not a choice the asker can make, while the row count stays as the database
     * saw it — two rows carrying one id is a real thing to know about the data.
     *
     * <p>{@code capped} says the probe hit its row limit, in which case the count is a floor and
     * is worded as one. A number presented as exact when it is a ceiling would be the same
     * confident-wrong this class exists to avoid.
     */
    public static String message(String label, List<String> values, boolean capped) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(values));
        List<String> shown = distinct.size() > MAX_LISTED ? distinct.subList(0, MAX_LISTED) : distinct;
        StringBuilder sb = new StringBuilder("That filter matches ")
            .append(capped ? "at least " : "").append(values.size())
            .append(" rows, so the question has no single answer. Matching ").append(label)
            .append(": ").append(String.join(", ", shown));
        if (distinct.size() > shown.size()) {
            sb.append(", and ").append(distinct.size() - shown.size()).append(" more");
        }
        return sb.append(". Ask again naming one of them — for example by filtering on ")
            .append(label).append(" instead of the name.").toString();
    }

}
