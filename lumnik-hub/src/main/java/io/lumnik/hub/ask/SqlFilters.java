package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the lexical shapes the ask pipeline reasons about — the bare-column filters that
 * {@link DomainGuard} and
 * {@link UndocumentedColumnGuard} both inspect: {@code col = 'v'} / {@code col <> 'v'} /
 * {@code col != 'v'} / {@code col IN ('a','b')} / {@code col NOT IN ('a','b')}. Purely LEXICAL — no policy: the guards apply their own
 * semantic checks (declared? in-domain? a niladic keyword?). A numeric RHS, a qualified or cast column,
 * a function-wrapped column, and a non-clean IN list never yield a filter (the deliberate fail-opens).
 * A QUOTED name is not among them: {@code SchemaCard} renders every column quoted, since that is the only
 * form a model can copy for a name carrying a dot, a space or a currency sign — so excluding quoted
 * identifiers would blind both guards to the filters the model actually writes.
 */
final class SqlFilters {

    private SqlFilters() {}

    /**
     * A column filter against one or more string literals. {@code column} is the original-case text with
     * any quotes stripped; {@code quoted} says how the query spelled it, which is the only thing that
     * separates a column NAMED like a keyword from the keyword itself — Postgres draws that line at the
     * quotes, and so must every reader of this record.
     */
    record Filter(String column, List<String> values, boolean quoted) {}

    /**
     * The parenthesised {@code SELECT} that follows an {@code =}, returned as its ORIGINAL text so
     * the caller can run it — {@code AmbiguousFilterGuard} uses it to say which rows a filter
     * matched when PostgreSQL refused a multi-row scalar subquery.
     *
     * <p>Token offsets are what make this a slice rather than a re-serialisation: {@code Token}
     * carries {@code [start, end)} precisely "so a caller can rewrite one token without re-scanning
     * the text". An earlier version hand-scanned instead, on the written claim that no offsets
     * existed — they do, and the scanner it justified had no idea what a comment was, so it read
     * {@code -- id = (SELECT …)} as code and offered ids from a query nobody ran.
     *
     * <p>Fail-open like the rest of this class: an unrecognised shape returns empty.
     */
    static Optional<String> scalarSubquery(String sql) {
        if (sql == null) return Optional.empty();
        List<Token> tokens = SqlLexer.tokenize(sql);
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!isOp(tokens.get(i), "=") || !isPunct(tokens.get(i + 1), "(")) continue;
            if (!isKeyword(tokens.get(i + 2), "select")) continue;
            int close = matchingParen(tokens, i + 1);
            if (close < 0) continue;
            return Optional.of(sql.substring(tokens.get(i + 1).end(), tokens.get(close).start()).trim());
        }
        return Optional.empty();
    }

    /** The index of the {@code )} closing the {@code (} at {@code open}, or -1 when unbalanced. */
    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (isPunct(tokens.get(i), "(")) depth++;
            else if (isPunct(tokens.get(i), ")") && --depth == 0) return i;
        }
        return -1;
    }

    /** Every literal filter the domain guard can enforce. (S3776: a guard chain plus two shapes.) */
    static List<Filter> columnLiteralFilters(List<Token> tokens) {
        List<Filter> filters = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!isColumnRef(tokens, i)) continue;
            filterAt(tokens, i).ifPresent(filters::add);
        }
        return filters;
    }

    /**
     * An identifier naming a column: quoted or not, but not qualified ({@code a.b}), not a cast target
     * ({@code ::type}), and not a BARE {@code NOT} — the logical operator lexes as an identifier, and
     * reading it as one made {@code segment NOT IN (…)} refuse a DECLARED column under the name "NOT".
     * Quoted, {@code "not"} is an ordinary column name to Postgres, so the exclusion tests
     * {@code !quoted()} — the shape {@link #isKeyword} two methods below has always used.
     *
     * <p>Quoted was excluded until the card started advertising quoted names (see the class comment).
     * Postgres resolves {@code "col"} to the same column as {@code col} for a lower-case name, both
     * guards fold case before comparing, and the lexer hands over the UNQUOTED text — so inspecting it
     * needs nothing else. Function-wrapping stays excluded, but by the token that FOLLOWS the name
     * ({@code lower(col)} is followed by {@code )}, never by an operator), not by this predicate.
     */
    private static boolean isColumnRef(List<Token> tokens, int i) {
        Token col = tokens.get(i);
        if (col.kind() != Kind.IDENT) return false;
        if (!col.quoted() && col.text().equalsIgnoreCase("not")) return false;
        Token prev = i > 0 ? tokens.get(i - 1) : null;
        return prev == null || !(isPunct(prev, ".") || isOp(prev, "::"));
    }

    /** {@code col = 'lit'} (or {@code <>}, {@code !=}), or {@code col IN ('a', 'b', …)}. */
    private static Optional<Filter> filterAt(List<Token> tokens, int i) {
        if (i + 1 >= tokens.size()) return Optional.empty();
        Token col = tokens.get(i);
        Token op = tokens.get(i + 1);
        if (isComparison(op)) {
            if (i + 2 < tokens.size() && tokens.get(i + 2).kind() == Kind.STRING) {
                return Optional.of(new Filter(col.text(), List.of(tokens.get(i + 2).text()), col.quoted()));
            }
            return Optional.empty();
        }
        if (isKeyword(op, "in") && i + 2 < tokens.size() && isPunct(tokens.get(i + 2), "(")) {
            return cleanStringList(tokens, i + 3).map(values -> new Filter(col.text(), values, col.quoted()));
        }
        // `col NOT IN ('a','b')` — the same filter, negated. The guards treat it like `<>` already does:
        // a value outside a declared domain is a value the model invented, whichever way the test runs.
        if (isKeyword(op, "not") && i + 3 < tokens.size() && isKeyword(tokens.get(i + 2), "in")
                && isPunct(tokens.get(i + 3), "(")) {
            return cleanStringList(tokens, i + 4).map(values -> new Filter(col.text(), values, col.quoted()));
        }
        return Optional.empty();
    }

    private static boolean isKeyword(Token t, String word) {
        return t.kind() == Kind.IDENT && !t.quoted() && t.text().equalsIgnoreCase(word);
    }

    private static boolean isComparison(Token op) {
        return op.kind() == Kind.OP
            && (op.text().equals("=") || op.text().equals("<>") || op.text().equals("!="));
    }

    /** From the token after {@code (}, read {@code STRING (, STRING)* )} — the values, or empty if not a
     *  clean string list (any non-string element, or unterminated). */
    private static Optional<List<String>> cleanStringList(List<Token> tokens, int start) {
        List<String> values = new ArrayList<>();
        int i = start;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (t.kind() != Kind.STRING) return Optional.empty();
            values.add(t.text());
            i++;
            if (i >= tokens.size()) return Optional.empty();
            Token sep = tokens.get(i);
            if (isPunct(sep, ")")) return Optional.of(values);
            if (!isPunct(sep, ",")) return Optional.empty();
            i++;
        }
        return Optional.empty();
    }

    private static boolean isPunct(Token t, String s) { return t.kind() == Kind.PUNCT && t.text().equals(s); }
    private static boolean isOp(Token t, String s) { return t.kind() == Kind.OP && t.text().equals(s); }
}
