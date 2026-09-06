package io.lumnik.hub.ask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p>This class owns how the failure is READ: {@link #causeChain} is the single bounded walk both
 * readers use. The SQL side of the disclosure — finding the subquery to re-run — belongs to
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

    /**
     * How far to walk a cause chain. A bound rather than a seen-set because it is cycle-proof
     * without allocating, and because a chain deeper than this is pathological either way: missing
     * a SQLState buried that far down costs the disclosure, never a wrong answer.
     */
    static final int MAX_CAUSE_DEPTH = 32;

    private AmbiguousFilterGuard() {
    }

    /**
     * The cause chain, depth-bounded — the ONE walk both readers below share.
     *
     * <p>Bounded rather than self-reference-guarded: {@code Throwable.initCause} refuses only
     * self-causation, so A→B→A is constructible in plain Java and an identity check leaves it
     * spinning on a request thread. Two hand-written walks lived here for one round and diverged
     * on exactly that point; one walk cannot.
     */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            chain.add(t);
            Throwable next = t.getCause();
            if (next == t) break;
            t = next;
        }
        return chain;
    }

    /** True when this failure is a scalar subquery that matched several rows. */
    public static boolean isAmbiguity(Throwable failure) {
        return causeChain(failure).stream().anyMatch(
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

    /**
     * SQLSTATE classes whose messages describe STRUCTURE, never stored values.
     *
     * <p>42 is <i>syntax error or access rule violation</i> — "relation … does not exist",
     * "column … does not exist", "permission denied for table …": identifiers, and the caller is
     * already holding the SQL they came from. 21 is <i>cardinality violation</i>, the failure that
     * prompted all of this. 53/54/57 are resources, limits and cancellation (our own 5s timeout).
     *
     * <p>Class 22 is deliberately absent, and its name is the whole argument: <i>data exception</i>.
     * {@code invalid input syntax for type bigint: "abc"} carries {@code abc} — a stored value,
     * reaching the caller through an error path rather than through an answer, outside the row
     * limits and the phrasing that govern one.
     *
     * <p>A list of known codes would be a shrug written out; this is a rule, so it decides the
     * codes nobody thought of. Unknown class, or no SQLSTATE at all: withhold.
     */
    static final Set<String> STRUCTURAL_CLASSES = Set.of("21", "42", "53", "54", "57");

    /**
     * The database's own words, first line only — the cause, not our guess at it, and not more of
     * it than the caller is owed.
     *
     * <p>Nothing is lost by withholding: {@code AskToSqlAgent} logs the whole exception with its
     * stack trace before calling this, so an operator keeps every character. What is bounded is
     * only what travels back through the API.
     */
    public static String rootMessage(Throwable failure) {
        List<Throwable> chain = causeChain(failure);
        if (chain.isEmpty()) return "(no cause)";
        Throwable root = chain.get(chain.size() - 1);
        if (root instanceof SQLException sql) {
            String state = sql.getSQLState();
            if (state == null || state.length() < 2 || !STRUCTURAL_CLASSES.contains(state.substring(0, 2))) {
                return "SQLSTATE " + (state == null ? "unknown" : state)
                    + " (the message is withheld — that class can carry stored values; the server log has it).";
            }
        }
        String m = root.getMessage();
        if (m == null || m.isBlank()) return root.getClass().getSimpleName() + ".";
        int nl = m.indexOf('\n');
        return (nl >= 0 ? m.substring(0, nl) : m).trim();
    }
}
