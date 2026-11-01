package io.lumnik.hub.sql;

import io.lumnik.common.error.Throwables;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
     *
     * <p>0A is <i>feature not supported</i>: it names a feature or a type, never a value. It is how a
     * {@code JdbcDialect} refuses a column it cannot read ("throw to refuse"), naming the column and its
     * type — a {@link SQLFeatureNotSupportedException} its provider may build with no SQLSTATE, read as
     * 0A by its class (#1648).
     */
    static final Set<String> STRUCTURAL_CLASSES = Set.of("0A", "21", "42", "53", "54", "57");

    /**
     * The database's own words, first line only — the cause, not our guess at it, and not more of
     * it than the caller is owed.
     *
     * <p>Nothing is lost by withholding: the caller logs the whole exception with its stack trace
     * before calling this, so an operator keeps every character. What is bounded is only what
     * travels back through the API.
     *
     * <p>The root is the deepest SQLException when the chain holds one: what the database said stops
     * at the database. A cause beneath it is the driver's own — a socket quoting {@code host:port} —
     * and is never served (review of #1648). Without a SQLException, the chain's last link.
     */
    public static String rootMessage(Throwable failure) {
        List<Throwable> chain = Throwables.causeChain(failure);
        return chain.isEmpty() ? "(no cause)" : said(readRoot(chain));
    }

    /** The link {@link #rootMessage} reads: the deepest SQLException, else the last link. */
    private static Throwable readRoot(List<Throwable> chain) {
        Throwable root = chain.getLast();
        for (Throwable t : chain) {
            if (t instanceof SQLException) root = t;
        }
        return root;
    }

    /** What a caller may read of one failure: a database failure's first line only when its class is
     *  structural, else its SQLSTATE; anything else's first line. */
    private static String said(Throwable t) {
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState() == null && sql instanceof SQLFeatureNotSupportedException
                ? "0A000" : sql.getSQLState();
            if (state == null || state.length() < 2 || !STRUCTURAL_CLASSES.contains(state.substring(0, 2))) {
                return "SQLSTATE " + (state == null ? "unknown" : state)
                    + " (the message is withheld — that class can carry stored values; the server log has it).";
            }
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getSimpleName() + ".";
        return firstLine(m);
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
        String top = ownMessage(failure);
        if (top == null) top = failure.getClass().getSimpleName();
        List<Throwable> chain = Throwables.causeChain(failure);
        if (chain.size() < 2) return top;
        String root = rootMessage(failure);
        // Already said once — a wrapper that quotes its cause, or names the SQLSTATE (or its absence) a
        // withheld root would only repeat (Jdbc.failureText's "… failed (SQLSTATE x)" / "(no SQLSTATE)").
        if (top.contains(root)) return top;
        if (readRoot(chain) instanceof SQLException sql && root.startsWith("SQLSTATE ")
                && top.contains(sql.getSQLState() == null ? "no SQLSTATE" : "SQLSTATE " + sql.getSQLState())) return top;
        return top + " — " + root;
    }

    /**
     * {@code failure}'s own message — cut where it starts repeating a database failure's words, with
     * what a caller may read of that failure in their place; null when it has none (#1648).
     *
     * <p>A wrapper that pasted {@code e.getMessage()} carried the driver's text past every rule here:
     * the root was withheld, its copy in the wrapper was not. pgjdbc's batch failure is the worst case —
     * {@code Batch entry 0 INSERT … VALUES (<the row>) was aborted: …}. Cut at the sink, whatever
     * catch wrote the wrapper; the first line of each SQLException's message is enough, its
     * {@code Detail:} lines follow it.
     */
    public static String ownMessage(Throwable failure) {
        String own = failure.getMessage();
        return own == null || own.isBlank() ? null : withoutDriverText(own, failure);
    }

    /**
     * {@code message} cut where it starts repeating the words of a SQLException in {@code failure}'s
     * chain, with what a caller may read of that SQLException in their place — for a sink that holds a
     * message and the failure it came from ({@code ConnectorEngine.finalizeRun}). Unchanged when it
     * repeats nothing. What precedes the cut loses its trailing separators and the driver's class name
     * a pasted {@code + e} brings ({@code "op: org.postgresql.util.PSQLException: …"}).
     */
    public static String withoutDriverText(String message, Throwable failure) {
        int cut = message.length();
        SQLException cutAt = null;
        for (Throwable t : Throwables.causeChain(failure)) {
            if (!(t instanceof SQLException sql) || t.getMessage() == null) continue;
            String words = firstLine(t.getMessage());
            int at = words.isEmpty() ? -1 : message.indexOf(words);
            if (at >= 0 && at < cut) {
                cut = at;
                cutAt = sql;
            }
        }
        if (cutAt == null) return message;
        String kept = TRAILING.matcher(message.substring(0, cut)).replaceAll("");
        return kept.isEmpty() ? said(cutAt) : kept + " — " + said(cutAt);
    }

    /** Separators, an opening bracket, or an exception's class name, at the end of a cut message. */
    private static final Pattern TRAILING =
        Pattern.compile("(?:[\\s:\\[(—-]|(?<![\\w.$])[\\w.$]*(?:Exception|Error))+$");

    private static String firstLine(String m) {
        int nl = m.indexOf('\n');
        return (nl >= 0 ? m.substring(0, nl) : m).trim();
    }
}
