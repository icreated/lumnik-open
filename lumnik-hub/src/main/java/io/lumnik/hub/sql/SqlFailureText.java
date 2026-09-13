package io.lumnik.hub.sql;

import java.sql.SQLException;
import java.util.ArrayList;
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
     * How far to walk a cause chain. A bound rather than a seen-set because it is cycle-proof
     * without allocating, and because a chain deeper than this is pathological either way: missing
     * a SQLState buried that far down costs the disclosure, never a wrong answer.
     */
    public static final int MAX_CAUSE_DEPTH = 32;

    /**
     * The cause chain, depth-bounded — the ONE walk every reader shares.
     *
     * <p>Bounded rather than self-reference-guarded: {@code Throwable.initCause} refuses only
     * self-causation, so A→B→A is constructible in plain Java and an identity check leaves it
     * spinning on a request thread. Two hand-written walks lived here for one round and diverged
     * on exactly that point; one walk cannot.
     */
    public static List<Throwable> causeChain(Throwable failure) {
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

    /**
     * The SQLSTATE of the first {@link SQLException} in the chain, or null when there is none.
     *
     * <p>Three hand-written copies of this walk existed — two spelled {@code isUniqueViolation},
     * one {@code sqlStateOf} — each re-deriving the loop {@link #causeChain} was written to retire,
     * and each unbounded. The bound is the whole point: A→B→A is constructible, and the readers
     * are a request thread, a scheduled sweep and a login path. One walk cannot diverge.
     */
    public static String sqlStateOf(Throwable failure) {
        for (Throwable t : causeChain(failure)) {
            if (t instanceof SQLException se && se.getSQLState() != null) return se.getSQLState();
        }
        return null;
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
     * <p>Nothing is lost by withholding: the caller logs the whole exception with its stack trace
     * before calling this, so an operator keeps every character. What is bounded is only what
     * travels back through the API.
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
        List<Throwable> chain = causeChain(failure);
        if (chain.size() < 2) return top;
        String root = rootMessage(failure);
        // Already said once — a wrapper that quotes its cause needs no echo of it.
        return top.contains(root) ? top : top + " — " + root;
    }
}
