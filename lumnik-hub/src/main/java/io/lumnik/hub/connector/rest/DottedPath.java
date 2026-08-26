package io.lumnik.hub.connector.rest;

/**
 * A "plain dotted path" — {@code $.A.B.C} with no JsonPath operators ({@code [ ] * ..}), no
 * whitespace, and no empty segments. SOAP's force-list ({@code XmlBodyParser}) can only walk such a
 * path, and the connector validator rejects anything else up front. Both sides share this one
 * predicate so the front-door rule and the runtime walk can never drift apart.
 */
public final class DottedPath {

    private DottedPath() {}

    /** True when {@code path} is {@code $.}-prefixed with no operators, whitespace, or empty segments. */
    public static boolean isPlain(String path) {
        if (path == null || !path.startsWith("$.")) return false;
        String p = path.substring(2);
        return !p.isBlank() && !p.startsWith(".") && !p.endsWith(".")
            && p.chars().noneMatch(Character::isWhitespace)
            && !p.contains("[") && !p.contains("*") && !p.contains("..");
    }

    /** The segments after the {@code $.} prefix, split on {@code .}. Requires an {@link #isPlain}
     *  path in full (not merely the {@code $.} prefix) — operators, whitespace, or empty segments are
     *  a call-site bug and fail fast rather than yielding a surprising segment array. */
    public static String[] segments(String path) {
        if (!isPlain(path)) {
            throw new IllegalArgumentException("segments() requires a plain dotted path ($.A.B), got: " + path);
        }
        return path.substring(2).split("\\.");
    }
}
