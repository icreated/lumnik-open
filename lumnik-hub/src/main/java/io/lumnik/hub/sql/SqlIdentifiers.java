package io.lumnik.hub.sql;

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

    /**
     * True when {@code ident} is a plain identifier — no quoting needed, nothing to escape. This is
     * a STRICTER check than the quoting above: it exists for manifest boundaries that build a new
     * identifier by string concatenation (e.g. {@code "t_" + name}) and then hand it to DDL, where
     * quoting alone is not enough because the concatenation itself must be safe to compose. It is
     * NOT a replacement for {@link #quoteIdent} as the general escaping defence — see the class
     * comment: quoting must still accept identifiers this predicate rejects (e.g. containing '-').
     *
     * <p>The rule is {@code [A-Za-z_][A-Za-z0-9_]*}, and that regex lives on as the oracle in
     * {@code PlainIdentifierTest}, whose sweep fails on any BMP string of three characters or
     * fewer that the two would judge differently. It is read character by character HERE because
     * a {@code Matcher} is an allocation: measured on a mixed column-name workload, 112.8 ns and
     * 128 B per call became 8.4 ns and none. Two shapes that look cheaper were measured SLOWER
     * and should not be retried — a 128-entry {@code boolean[]} lookup (5.4 ns) and a single loop
     * from index 0 with an {@code i > 0} guard on the digit range (5.6 ns), against 5.2 for this.
     *
     * <p>What makes the allocation worth removing is one call site, and that site is the open
     * item, not the achievement: {@code MapColumnsHook} is a {@code RowHook}, so it re-validates
     * a manifest node that cannot change between rows — once per rename entry per row, around an
     * {@code ArrayList}, a {@code HashSet} and a Jackson {@code fields()} iterator it also
     * allocates per row. The predicate is cold at the other nine sites. The fix is one level up
     * (validate the params once per spec, which needs a seam {@code RowHook} does not have yet);
     * this only stops the smallest of those allocations from being per-row too.
     *
     * <p>ASCII on purpose. {@code Character.isLetterOrDigit} would accept {@code café} and a
     * Cyrillic {@code а} that renders identically to the Latin one — a homoglyph in a name the
     * hub concatenates into DDL. Pinned in both directions by the test's character sweep.
     */
    public static boolean isPlainIdentifier(String ident) {
        if (ident == null || ident.isEmpty()) return false;
        if (!isLetterOrUnderscore(ident.charAt(0))) return false;
        for (int i = 1, n = ident.length(); i < n; i++) {
            char c = ident.charAt(i);
            if (!isLetterOrUnderscore(c) && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    private static boolean isLetterOrUnderscore(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    /**
     * True when {@code fqn} is a plain qualified table reference: {@code schema.table} where both
     * parts are plain identifiers (see {@link #isPlainIdentifier}), or a bare plain identifier with
     * no dot. Splits on the first dot only, mirroring {@link #quoteTable} — and that mirroring is
     * now pinned rather than merely stated: {@code PlainIdentifierTest} asserts that whatever this
     * approves, {@code quoteTable} renders as plain segments only. Moving the renderer's boundary
     * fails that test; moving this one cannot change a verdict, since more than one dot leaves a
     * dot in a segment whichever boundary you pick.
     *
     * <p>The two {@code substring} calls allocate, and stay: every caller is apply-time or
     * once-per-batch, and the substring form is what makes the shared split visible at a glance.
     */
    public static boolean isPlainQualifiedIdentifier(String fqn) {
        if (fqn == null) return false;
        int dot = fqn.indexOf('.');
        if (dot < 0) return isPlainIdentifier(fqn);
        return isPlainIdentifier(fqn.substring(0, dot)) && isPlainIdentifier(fqn.substring(dot + 1));
    }
}
