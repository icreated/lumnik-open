package io.lumnik.hub.sql;

import java.util.regex.Pattern;

/**
 * PostgreSQL identifier quoting for the raw-JDBC layer (the readers/consumers that
 * build dynamic SQL over generic, non-{@code @Entity} schema — fused views, connector
 * tables, the event log). One source of truth so the escaping cannot drift between
 * copies: a divergence here is a SQL-injection surface, not a style nit.
 *
 * <p>Quoting is the escaping defence, NOT a character allowlist — an allowlist wrongly
 * rejects valid Postgres identifiers (e.g. names containing '-').
 */
public final class SqlIdentifiers {

    private SqlIdentifiers() {
    }

    /** Double-quote an identifier, doubling any embedded double-quote. */
    public static String quoteIdent(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    /**
     * Quote a possibly-qualified table reference: {@code schema.table} → both parts
     * quoted, a bare name → quoted whole. Splits on the first dot only.
     */
    public static String quoteTable(String fqn) {
        int dot = fqn.indexOf('.');
        if (dot < 0) return quoteIdent(fqn);
        return quoteIdent(fqn.substring(0, dot)) + "." + quoteIdent(fqn.substring(dot + 1));
    }

    /** An unquoted-safe identifier: starts with an ASCII letter/underscore, then letters/digits/underscore. */
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * True when {@code ident} is a plain identifier — no quoting needed, nothing to escape. This is
     * a STRICTER check than the quoting above: it exists for manifest boundaries that build a new
     * identifier by string concatenation (e.g. {@code "t_" + name}) and then hand it to DDL, where
     * quoting alone is not enough because the concatenation itself must be safe to compose. It is
     * NOT a replacement for {@link #quoteIdent} as the general escaping defence — see the class
     * comment: quoting must still accept identifiers this predicate rejects (e.g. containing '-').
     */
    public static boolean isPlainIdentifier(String ident) {
        return ident != null && PLAIN_IDENTIFIER.matcher(ident).matches();
    }

    /**
     * True when {@code fqn} is a plain qualified table reference: {@code schema.table} where both
     * parts are plain identifiers (see {@link #isPlainIdentifier}), or a bare plain identifier with
     * no dot. Splits on the first dot only, mirroring {@link #quoteTable}.
     */
    public static boolean isPlainQualifiedIdentifier(String fqn) {
        if (fqn == null) return false;
        int dot = fqn.indexOf('.');
        if (dot < 0) return isPlainIdentifier(fqn);
        return isPlainIdentifier(fqn.substring(0, dot)) && isPlainIdentifier(fqn.substring(dot + 1));
    }
}
