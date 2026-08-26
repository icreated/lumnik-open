package io.lumnik.hub.ask;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal, ITERATIVE SQL lexer for the honesty guards — NOT a parser (no grammar, no AST, no
 * clause-awareness). It classifies the lexical concerns the guards used to re-solve with divergent
 * regexes: string literals (with '' escapes), comments (skipped), quoted identifiers, casts (::), numbers,
 * and punctuation. Total over any input (never throws). Pure.
 */
final class SqlLexer {

    private SqlLexer() {}

    enum Kind { IDENT, STRING, NUMBER, OP, PUNCT }

    /** A lexical token. IDENT: text = the (unquoted) name, quoted = was it {@code "..."}. STRING: text = the
     *  UNESCAPED value. NUMBER/OP/PUNCT: text = the raw lexeme. */
    record Token(Kind kind, String text, boolean quoted) {}

    /** Two-char operators, checked before single chars. None is a prefix of another, so order is free. */
    private static final String[] TWO_CHAR_OPS = {"::", "<>", "!=", "<=", ">="};

    /** The punctuation the guards care about; everything else falls through to a one-char OP. */
    private static final String PUNCT_CHARS = ".(),";

    /**
     * The scan loop. Each reader below consumes ONE token and returns the index it stopped at —
     * the standard scanner contract, and what keeps this loop a dispatch rather than a wall.
     *
     * <p>Extraction took this from 82 to 17. The remainder is irreducible: what is left IS the number
     * of token shapes, and the branch ORDER is load-bearing — {@code --} must be tested before
     * {@code -}, {@code /*} before {@code /}, two-char operators before single chars. A dispatch table
     * would hide exactly the sequence a reader needs to verify, and buy nothing but the metric.
     */
    @SuppressWarnings("java:S3776")
    static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        if (sql == null) return out;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c))       { i++; continue; }
            if (sql.startsWith("--", i))         { i = skipLineComment(sql, i, n); continue; }
            if (sql.startsWith("/*", i))         { i = skipBlockComment(sql, i, n); continue; }
            if (c == '\'')                       { i = readString(sql, i, n, out); continue; }
            if (c == '"')                        { i = readQuotedIdent(sql, i, n, out); continue; }
            if (c == '_' || isAsciiLetter(c))    { i = readIdent(sql, i, n, out); continue; }
            if (isAsciiDigit(c))                 { i = readNumber(sql, i, n, out); continue; }
            i = readOperatorOrPunct(sql, i, out);
        }
        return out;
    }

    /** {@code -- … EOL}. Stops AT the newline, which the loop then skips as whitespace. */
    private static int skipLineComment(String sql, int i, int n) {
        i += 2;
        while (i < n && sql.charAt(i) != '\n') i++;
        return i;
    }

    /** <code>/* … *&#47;</code>. An unterminated block consumes the rest of the input. */
    private static int skipBlockComment(String sql, int i, int n) {
        i += 2;
        while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
        return Math.min(i + 2, n);
    }

    /** {@code '…'} with the {@code ''} escape; the token carries the UNESCAPED value. */
    private static int readString(String sql, int i, int n, List<Token> out) {
        StringBuilder sb = new StringBuilder();
        i = readDelimited(sql, i, n, '\'', sb);
        out.add(new Token(Kind.STRING, sb.toString(), false));
        return i;
    }

    /** {@code "…"} with the {@code ""} escape; the token carries the unquoted name. */
    private static int readQuotedIdent(String sql, int i, int n, List<Token> out) {
        StringBuilder sb = new StringBuilder();
        i = readDelimited(sql, i, n, '"', sb);
        out.add(new Token(Kind.IDENT, sb.toString(), true));
        return i;
    }

    /**
     * Shared body of the two delimited forms: a doubled delimiter is one literal delimiter, and an
     * unterminated literal ends at the input. Total — never throws.
     */
    private static int readDelimited(String sql, int i, int n, char delim, StringBuilder sb) {
        i++;
        while (i < n) {
            char d = sql.charAt(i);
            if (d == delim) {
                if (i + 1 < n && sql.charAt(i + 1) == delim) { sb.append(delim); i += 2; continue; }
                i++; break;
            }
            sb.append(d); i++;
        }
        return i;
    }

    /**
     * {@code [A-Za-z_][A-Za-z0-9_]*} — ASCII only, matching the guards' prior regex. A non-ASCII
     * char ends the identifier, so a non-ASCII name stays fail-open and never becomes a new refusal.
     */
    private static int readIdent(String sql, int i, int n, List<Token> out) {
        int start = i;
        while (i < n && (sql.charAt(i) == '_' || isAsciiLetterOrDigit(sql.charAt(i)))) i++;
        out.add(new Token(Kind.IDENT, sql.substring(start, i), false));
        return i;
    }

    /** {@code [0-9][0-9.]*} — ASCII digits only, matching the prior regex. */
    private static int readNumber(String sql, int i, int n, List<Token> out) {
        int start = i;
        while (i < n && (isAsciiDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
        out.add(new Token(Kind.NUMBER, sql.substring(start, i), false));
        return i;
    }

    /** A two-char operator, punctuation, or — as the total fallback — any single char as an OP. */
    private static int readOperatorOrPunct(String sql, int i, List<Token> out) {
        for (String op : TWO_CHAR_OPS) {
            if (sql.startsWith(op, i)) {
                out.add(new Token(Kind.OP, op, false));
                return i + 2;
            }
        }
        char c = sql.charAt(i);
        Kind kind = PUNCT_CHARS.indexOf(c) >= 0 ? Kind.PUNCT : Kind.OP;
        out.add(new Token(kind, String.valueOf(c), false));
        return i + 1;
    }

    private static boolean isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private static boolean isAsciiDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isAsciiLetterOrDigit(char c) { return isAsciiLetter(c) || isAsciiDigit(c); }
}
