package io.lumnik.extraction.spi.ask;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validator/normalizer for LLM-generated SQL.
 * Layers 2 (banned-token denylist) and 4 (LIMIT bounding) of a five-layer defense in depth:
 * layer 1 is the read-only datasource the query executes against, layer 3 is schema
 * confinement at prompt time, and layer 5 is echoing the executed SQL back in the response
 * so nothing runs unseen.
 *
 * <p>Both layers read {@link SqlLexer} tokens, never the raw text — the same lexer the three
 * schema guards use. Reading raw text made the guard blind in both directions at once: a banned
 * word or a {@code ;} inside a string literal refused an honest question, while a trailing
 * {@code --} comment put the injected LIMIT inside the comment and lifted the bound entirely.
 */
public class SqlGuard {

    public static class SqlRejectedException extends RuntimeException {
        public SqlRejectedException(String msg) { super(msg); }
    }

    /**
     * Verbs. Denied only as BARE identifiers: Postgres never promotes a double-quoted token to a
     * keyword, so a quoted {@code "insert"} is a column name and refusing it would over-block.
     */
    private static final Set<String> BANNED_VERBS = Set.of(
        "insert", "update", "delete", "drop", "alter", "create", "truncate", "grant", "revoke",
        "copy", "vacuum", "reindex");

    /**
     * Objects and functions. Denied QUOTED OR NOT — here the quotes name the very thing the entry
     * exists to deny, so exempting them would reopen the denylist rather than avoid an over-block.
     *
     * <p>{@code set_config} rewrites {@code app.current_tenant} inside the query, so RLS filters on the
     * tenant the query chose; {@code ts_stat}, {@code ts_rewrite} and the {@link #XML_FAMILY} run SQL held in a string
     * literal, which no guard reads (#1987). The boundary is the database, which revokes them from the
     * readonly role ({@code infra/postgres/init/02-extensions.sql}); this list is the layer above it.
     */
    private static final Set<String> BANNED_OBJECTS = Set.of(
        "information_schema", "lo_import", "lo_export", "dblink", "set_config", "ts_stat", "ts_rewrite");

    /** {@code query_to_xml}, {@code table_to_xmlschema}…: the same pattern {@code 02-extensions.sql} revokes. */
    private static final Pattern XML_FAMILY = Pattern.compile("^(query|table|cursor|schema|database)_to_xml");

    /**
     * The server's own namespace: {@code pg_catalog} itself, and the catalog it resolves even under the
     * empty {@code search_path} ({@code pg_class}, {@code pg_stat_activity}…), the sleeps, the file reads,
     * the advisory locks that outlive the query on a pooled connection, the backend signals. A prefix,
     * because the exact names left {@code pg_sleep_for} open beside {@code pg_sleep}. The cost: a
     * business column whose name starts with {@code pg_} is refused too.
     */
    private static final String BANNED_PREFIX = "pg_";

    /** Beyond this many digits the bound is nonsense anyway, and {@code parseInt} would throw. */
    private static final int MAX_LIMIT_DIGITS = 9;

    private final int maxRows;

    public SqlGuard(int maxRows) { this.maxRows = maxRows; }

    /** Returns the normalized, LIMIT-bounded SQL, or throws SqlRejectedException. */
    public String check(String raw) {
        if (raw == null || raw.isBlank()) throw new SqlRejectedException("empty SQL");

        String sql = stripFencesAndTrailingSemicolon(raw);
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(sql);
        if (tokens.isEmpty()) throw new SqlRejectedException("empty SQL");

        requireSingleStatement(tokens);
        requireNoDollarQuoting(tokens);
        requireOnlyPlainLiterals(tokens);
        requireSelectOrWith(tokens);
        requireNoBannedName(tokens);
        return bound(sql, tokens);
    }

    private static String stripFencesAndTrailingSemicolon(String raw) {
        String sql = raw.trim()
            .replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
            .replaceAll("(?s)\\s*```$", "")
            .trim();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
    }

    /** A {@code ;} that is a real token — one inside a literal is data, not a second statement. */
    private static void requireSingleStatement(List<SqlLexer.Token> tokens) {
        for (SqlLexer.Token t : tokens) {
            if (isOp(t, ";")) throw new SqlRejectedException("multiple statements not allowed");
        }
    }

    /** The first TOKEN, so a leading comment no longer disguises an honest SELECT as a refusal. */
    private static void requireSelectOrWith(List<SqlLexer.Token> tokens) {
        SqlLexer.Token first = tokens.get(0);
        if (!isWord(first) || !(first.text().equalsIgnoreCase("select") || first.text().equalsIgnoreCase("with"))) {
            throw new SqlRejectedException("only SELECT/WITH allowed");
        }
    }

    /**
     * Dollar quoting is a second string syntax this lexer does not read, and an apostrophe inside a
     * {@code $$…$$} body opens a lexer string over real code — which is how a second statement hid
     * from {@link #requireSingleStatement}. The generation prompt never emits {@code $}, so refusing
     * it costs nothing and closes the disagreement rather than trying to out-guess it.
     */
    private static void requireNoDollarQuoting(List<SqlLexer.Token> tokens) {
        for (SqlLexer.Token t : tokens) {
            if (isOp(t, "$")) throw new SqlRejectedException("dollar-quoted text is not allowed");
        }
    }

    /**
     * The lexer knows one escape, the doubled delimiter (#1988). Before {@code E'\''} it closes the
     * string after the backslash where the server does not, and the quote after it opens a lexer string
     * over real code; {@code U&"c\006fnnector"} is the schema {@code connector} to the server and an
     * unknown name to every guard. Same choice as {@link #requireNoDollarQuoting}: refuse the forms,
     * with or without a space before the quote ({@code UESCAPE} only follows {@code U&}).
     *
     * <p>Every other string the lexer reads as the server does: the readonly transaction pins
     * {@code standard_conforming_strings=on} ({@code TenantRlsScope}), so a backslash in a plain string is
     * data to both, and {@code LIKE '50\%'} or {@code ~ '^\d+'} stay open. {@code B'…'}, {@code X'…'} and
     * {@code N'…'} quote exactly like a plain string.
     */
    private static void requireOnlyPlainLiterals(List<SqlLexer.Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            SqlLexer.Token t = tokens.get(i);
            if (isWord(t) && prefixesALiteral(tokens, i, t.text().toLowerCase(Locale.ROOT))) {
                throw new SqlRejectedException("a literal form the guard does not read");
            }
        }
    }

    private static boolean prefixesALiteral(List<SqlLexer.Token> tokens, int at, String word) {
        if (word.equals("e")) return isString(tokens, at + 1);
        return word.equals("u") && at + 1 < tokens.size() && isOp(tokens.get(at + 1), "&")
            && (isString(tokens, at + 2) || at + 2 < tokens.size() && tokens.get(at + 2).quoted());
    }

    private static boolean isString(List<SqlLexer.Token> tokens, int at) {
        return at < tokens.size() && tokens.get(at).kind() == SqlLexer.Kind.STRING;
    }

    private static boolean isOp(SqlLexer.Token t, String op) {
        return t.kind() == SqlLexer.Kind.OP && op.equals(t.text());
    }

    private static void requireNoBannedName(List<SqlLexer.Token> tokens) {
        for (SqlLexer.Token t : tokens) {
            if (t.kind() != SqlLexer.Kind.IDENT) continue;
            String name = t.text().toLowerCase(Locale.ROOT);
            if (BANNED_OBJECTS.contains(name) || name.startsWith(BANNED_PREFIX) || XML_FAMILY.matcher(name).find()
                    || (!t.quoted() && BANNED_VERBS.contains(name))) {
                throw new SqlRejectedException("banned token in SQL");
            }
        }
    }

    /** A bare, unquoted identifier — the only shape Postgres reads as a keyword. */
    private static boolean isWord(SqlLexer.Token t) {
        return t.kind() == SqlLexer.Kind.IDENT && !t.quoted();
    }

    /**
     * Caps an existing bound in place, or appends one. The appended LIMIT goes on its OWN LINE:
     * the query may end in a {@code --} comment, and a bound inside a comment is not a bound.
     */
    private String bound(String sql, List<SqlLexer.Token> tokens) {
        int at = lastLimitValueIndex(tokens);
        if (at < 0) return sql + "\nLIMIT " + maxRows;

        SqlLexer.Token value = tokens.get(at);
        if (!exceedsCap(value.text())) return sql;
        return sql.substring(0, value.start()) + maxRows + sql.substring(value.end());
    }

    /**
     * Index of the NUMBER token of the bound that binds the ANSWER, or -1.
     *
     * <p>Anchored at the tail, which is the only position where a {@code LIMIT} governs the outermost
     * query. Accepting one from anywhere let {@code WITH x AS (SELECT 1 LIMIT 1) SELECT * FROM t}
     * satisfy the guard while the outer query stayed unbounded — the bound this layer exists to add
     * was suppressed by a decoy. A trailing {@code OFFSET n} still leaves the LIMIT outermost.
     */
    private static int lastLimitValueIndex(List<SqlLexer.Token> tokens) {
        int end = tokens.size();
        if (isPairAt(tokens, end - 2, "offset")) end -= 2;
        return isPairAt(tokens, end - 2, "limit") ? end - 1 : -1;
    }

    /** Is {@code tokens[at]} the bare keyword {@code word} followed by a NUMBER? */
    private static boolean isPairAt(List<SqlLexer.Token> tokens, int at, String word) {
        return at >= 0 && at + 1 < tokens.size()
            && isWord(tokens.get(at)) && tokens.get(at).text().equalsIgnoreCase(word)
            && tokens.get(at + 1).kind() == SqlLexer.Kind.NUMBER;
    }

    /**
     * Total over any NUMBER lexeme. The lexer's NUMBER is {@code [0-9][0-9.]*}, so a fractional or
     * dot-only bound reaches here; anything that is not a plain integer we cannot honour is capped
     * rather than parsed, because a bound the guard cannot read is not a bound it can trust.
     */
    private boolean exceedsCap(String lexeme) {
        int dot = lexeme.indexOf('.');
        String whole = dot < 0 ? lexeme : lexeme.substring(0, dot);
        return whole.isEmpty() || whole.length() > MAX_LIMIT_DIGITS || Integer.parseInt(whole) > maxRows;
    }
}
