package io.lumnik.hub.sql;

/**
 * The one rule by which a join key is compared (#864), shared by every place that matches a lookup key:
 * the compiled entity join and the search-projection reverse scans. A key that is a canonical decimal
 * number is compared by value, so {@code 1000021}, {@code 1000021.0} and {@code 1000021.0000} are one
 * key; anything else is compared as its text, exactly. By shape, not by column type — see docs/entities.md.
 */
public final class JoinKeys {

    private JoinKeys() {
    }

    /** No leading zero (so {@code 007} stays text), at most 38 digits each side of the point. */
    static final String NUMERIC_KEY_PATTERN = "^-?(0|[1-9][0-9]{0,37})([.][0-9]{1,38})?$";

    /**
     * The comparison expression of a key column, given as an already-quoted SQL expression (e.g.
     * {@code "cid"} or {@code s."cid"}); the {@code ::text} cast is added here. The {@code CASE} is what
     * keeps the {@code ::numeric} cast away from non-numeric text.
     */
    public static String normalised(String column) {
        String text = column + "::text";
        return "(CASE WHEN " + text + " ~ '" + NUMERIC_KEY_PATTERN + "'"
            + " THEN trim_scale((" + text + ")::numeric)::text ELSE " + text + " END)";
    }
}
