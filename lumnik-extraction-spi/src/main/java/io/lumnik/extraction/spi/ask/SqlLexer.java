package io.lumnik.extraction.spi.ask;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal, ITERATIVE SQL lexer for the honesty guards — NOT a parser (no grammar, no AST, no
 * clause-awareness). It classifies the lexical concerns the guards used to re-solve with divergent
 * regexes: string literals (with '' escapes), comments (skipped), quoted identifiers, casts (::), numbers,
 * and punctuation. Total over any input (never throws). Pure.
 */
public final class SqlLexer {

    private SqlLexer() {}

    public enum Kind { IDENT, STRING, NUMBER, OP, PUNCT }

    /** A lexical token. IDENT: text = the (unquoted) name, quoted = was it {@code "..."}. STRING: text = the
     *  UNESCAPED value. NUMBER/OP/PUNCT: text = the raw lexeme. {@code [start, end)} is the token's
     *  half-open span in the input, so a caller can rewrite one token without re-scanning the text. */
    public record Token(Kind kind, String text, boolean quoted, int start, int end) {}

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
    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        if (sql == null) return out;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (isAsciiWhitespace(c))            { i++; continue; }
            if (sql.startsWith("--", i))         { i = skipLineComment(sql, i, n); continue; }
            if (sql.startsWith("/*", i))         { i = skipBlockComment(sql, i, n); continue; }
            if (c == '\'')                       { i = readString(sql, i, n, out); continue; }
            if (c == '"')                        { i = readQuotedIdent(sql, i, n, out); continue; }
            if (isIdentStart(c))                 { i = readIdent(sql, i, n, out); continue; }
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

    /**
     * <code>/* … *&#47;</code>, NESTING as Postgres does — {@code /* /* *&#47; *&#47;} is one comment,
     * and the inner close does not end it. A non-nesting skip ended the comment EARLY, so a lone
     * quote in the gap opened a lexer string over text Postgres was still reading as code, hiding a
     * statement separator from the guard. An unterminated block still consumes the rest of the input.
     */
    private static int skipBlockComment(String sql, int i, int n) {
        int depth = 0;
        while (i < n) {
            if (sql.startsWith("/*", i)) { depth++; i += 2; continue; }
            if (sql.startsWith("*/", i)) {
                depth--; i += 2;
                if (depth == 0) return i;
                continue;
            }
            i++;
        }
        return n;
    }

    /** {@code '…'} with the {@code ''} escape; the token carries the UNESCAPED value. */
    private static int readString(String sql, int i, int n, List<Token> out) {
        StringBuilder sb = new StringBuilder();
        int start = i;
        i = readDelimited(sql, i, n, '\'', sb);
        out.add(new Token(Kind.STRING, sb.toString(), false, start, i));
        return i;
    }

    /** {@code "…"} with the {@code ""} escape; the token carries the unquoted name. */
    private static int readQuotedIdent(String sql, int i, int n, List<Token> out) {
        StringBuilder sb = new StringBuilder();
        int start = i;
        i = readDelimited(sql, i, n, '"', sb);
        out.add(new Token(Kind.IDENT, sb.toString(), true, start, i));
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
     * A Postgres identifier, whose character class is defined by {@link #isIdentStart} below — the
     * server's, not Java's.
     *
     * <p>This was ASCII-only, on the theory that a non-ASCII name stayed fail-open. It did not. An
     * accent only ends the identifier — the LETTERS AFTER IT start a fresh one, so {@code crédit = 'X'}
     * offered the guards a bare {@code dit} sitting against the {@code =} and earned a refusal naming a
     * column nobody wrote. The old claim held only when the accent happened to fall last, which is
     * exactly the sample the lexer test used.
     */
    private static int readIdent(String sql, int i, int n, List<Token> out) {
        int start = i;
        while (i < n && isIdentPart(sql.charAt(i))) i++;
        out.add(new Token(Kind.IDENT, sql.substring(start, i), false, start, i));
        return i;
    }

    /** {@code [0-9][0-9.]*} — ASCII digits only, matching the prior regex. */
    private static int readNumber(String sql, int i, int n, List<Token> out) {
        int start = i;
        while (i < n && (isAsciiDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
        out.add(new Token(Kind.NUMBER, sql.substring(start, i), false, start, i));
        return i;
    }

    /** A two-char operator, punctuation, or — as the total fallback — any single char as an OP. */
    private static int readOperatorOrPunct(String sql, int i, List<Token> out) {
        for (String op : TWO_CHAR_OPS) {
            if (sql.startsWith(op, i)) {
                out.add(new Token(Kind.OP, op, false, i, i + 2));
                return i + 2;
            }
        }
        char c = sql.charAt(i);
        Kind kind = PUNCT_CHARS.indexOf(c) >= 0 ? Kind.PUNCT : Kind.OP;
        out.add(new Token(kind, String.valueOf(c), false, i, i + 1));
        return i + 1;
    }

    /**
     * The identifier character class is the SERVER's: PostgreSQL's scanner reads
     * {@code [A-Za-z_\200-\377]} to start a name and adds digits and {@code $} to continue it — any byte
     * with the high bit set is an identifier char to it, letter or not. Measured on PostgreSQL 16, each
     * created quoted then read UNQUOTED: {@code a€b}, {@code a$b}, {@code a«b}, {@code aéb}, and even
     * {@code a<U+3000>b} and {@code a<U+00A0>b} all resolve; only a leading {@code $} is a syntax error.
     *
     * <p>{@code Character.isLetter} was the wrong oracle, and it failed the same way the ASCII-only
     * version before it did (see {@link #readIdent}): the chars it rejected did not stay fail-open, they
     * ENDED the name and let the tail start a fresh one, so {@code montant_€_ht = '42'} offered the guards
     * a bare {@code _ht} and earned a refusal naming a column nobody wrote — while the message listed the
     * real column as known, in the same sentence. Widening to the server's class is what removes the
     * phantom rather than papering over it.
     *
     * <p>{@code $} in CONTINUATION ONLY is load-bearing, not a detail: it is why widening does not blind
     * {@code SqlGuard}'s dollar-quoting refusal. The opening {@code $} of a {@code $$…$$} or
     * {@code $tag$…$tag$} body can never be absorbed into a name, so it still arrives as a bare OP
     * token. The case this does NOT cover is the one where agreement makes it moot: in {@code a$tag$…}
     * the {@code $} does get absorbed, and the server absorbs it too (its scanner takes the longest
     * match at that position), so what we read as one name it also reads as one name.
     */
    private static boolean isIdentStart(char c) {
        return c == '_' || isAsciiLetter(c) || c >= 0x80;
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isAsciiDigit(c) || c == '$';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * The five characters the SERVER separates tokens on, spelled out rather than delegated: measured on
     * PostgreSQL 16, {@code a<FF>b} splits into two names while {@code a<VT>b} and {@code a<FS>b} are a
     * syntax error — the server treats VT and the FS/GS/RS/US controls as neither whitespace nor
     * identifier characters. {@code Character.isWhitespace} is true for all six, so delegating to it
     * would have left this predicate wider than the sentence describing it, and Java is not the oracle
     * here. It matters at all because this branch runs BEFORE the identifier branch: Java also calls
     * U+2028 and U+3000 whitespace, and the server calls them identifier characters, so a name carrying
     * one would keep being split however wide {@link #isIdentStart} grew.
     */
    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** Numbers stay ASCII: a non-ASCII digit is not a bound, a page size, or anything we should read. */
    private static boolean isAsciiDigit(char c) { return c >= '0' && c <= '9'; }
}
