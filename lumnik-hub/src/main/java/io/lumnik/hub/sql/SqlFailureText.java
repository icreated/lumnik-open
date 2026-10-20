package io.lumnik.hub.sql;

import io.lumnik.common.error.Throwables;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * How a failure carrying a database cause is read, and how much of it may travel to a caller.
 *
 * <p>The rule was written for the ask lane, where an LLM-generated query fails and the operator
 * needs to know why without the answer becoming a channel for stored values. Ingestion needs the
 * identical rule for the identical reason, so it lives here rather than in either lane.
 */
public final class SqlFailureText {

    private SqlFailureText() {}

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
     * <p>Nothing is lost by withholding: the caller logs the whole exception with its stack trace
     * before calling this, so an operator keeps every character. What is bounded is only what
     * travels back through the API.
     */
    public static String rootMessage(Throwable failure) {
        List<Throwable> chain = Throwables.causeChain(failure);
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

    /**
     * What an operator should be told a run failed of: our own message, plus the root cause's when
     * the root actually says something our message does not.
     *
     * <p>The case that forced this: a promoted column typed {@code timestamptz} against a JSON
     * string yields {@code column "ordered_at" is of type timestamp with time zone but expression
     * is of type character varying} — SQLSTATE 42804, a sentence about structure that names the
     * column and the two types and carries no row values. The operator received only
     * {@code HybridJsonbWriter failed for ext.…}, which names the table and nothing they could act
     * on. The database had already written the answer; a wrapper was standing in front of it.
     */
    public static String withCause(Throwable failure) {
        String top = failure.getMessage();
        if (top == null || top.isBlank()) top = failure.getClass().getSimpleName();
        List<Throwable> chain = Throwables.causeChain(failure);
        if (chain.size() < 2) return top;
        String root = rootMessage(failure);
        // Already said once — a wrapper that quotes its cause needs no echo of it.
        return top.contains(root) ? top : top + " — " + root;
    }
}
