package io.lumnik.hub.ask;

import io.lumnik.hub.ask.SqlLexer.Kind;
import io.lumnik.hub.ask.SqlLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the bare-column = string-literal filters that {@link DomainGuard} and
 * {@link UndocumentedColumnGuard} both inspect: {@code col = 'v'} / {@code col <> 'v'} /
 * {@code col != 'v'} / {@code col IN ('a','b')}. Purely LEXICAL — no policy: the guards apply their own
 * semantic checks (declared? in-domain? a niladic keyword?). A numeric RHS, a qualified/quoted/cast column,
 * a function-wrapped column, and a non-clean IN list never yield a filter (the deliberate fail-opens).
 */
final class SqlFilters {

    private SqlFilters() {}

    /** A bare-column filter against one or more string literals. {@code column} is the original-case text. */
    record Filter(String column, List<String> values) {}

    /** Every literal filter the domain guard can enforce. (S3776: a guard chain plus two shapes.) */
    static List<Filter> columnLiteralFilters(List<Token> tokens) {
        List<Filter> filters = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!isBareColumnRef(tokens, i)) continue;
            filterAt(tokens, i).ifPresent(filters::add);
        }
        return filters;
    }

    /** A bare identifier: not quoted, not qualified ({@code a.b}), not a cast target ({@code ::type}). */
    private static boolean isBareColumnRef(List<Token> tokens, int i) {
        Token col = tokens.get(i);
        if (col.kind() != Kind.IDENT || col.quoted()) return false;
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
                return Optional.of(new Filter(col.text(), List.of(tokens.get(i + 2).text())));
            }
            return Optional.empty();
        }
        if (op.kind() == Kind.IDENT && op.text().equalsIgnoreCase("in")
                && i + 2 < tokens.size() && isPunct(tokens.get(i + 2), "(")) {
            return cleanStringList(tokens, i + 3).map(values -> new Filter(col.text(), values));
        }
        return Optional.empty();
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
