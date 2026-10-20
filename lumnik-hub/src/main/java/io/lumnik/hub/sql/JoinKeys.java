package io.lumnik.hub.sql;

/**
 * The one rule by which a join key is compared (#864), shared by every place that matches a lookup key:
 * the compiled entity join and the search-projection reverse scans. A key that is a canonical decimal
 * number is compared by value, so {@code 1000021}, {@code 1000021.0} and {@code 1000021.0000} are one
 * key; anything else is compared as its text, exactly — by shape, not by column type — except two dates or
 * times, which meet at the day or the instant ({@link #compared}). See docs/entities.md.
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

    /**
     * The comparison expression of a key whose side is typed {@code type} and is compared to a side typed
     * {@code other} — type names as the catalog spells them ({@code date}, {@code timestamp}, {@code timestamptz};
     * anything else, or null, is text). Two dates or times meet as dates (#1329): at the UTC day when either side is
     * a {@code date} — a lookup keyed by day is a calendar, and a source that only knows the day cannot contradict an
     * hour (#1327) — as instants otherwise, a naive timestamp read as UTC. A key with a text side is compared by
     * {@link #normalised}, exactly as before.
     */
    public static String compared(String column, String type, String other) {
        if (!temporal(type) || !temporal(other)) return normalised(column);
        if ("date".equals(type) || "date".equals(other)) {
            return switch (type) {
                case "date" -> column;
                case "timestamp" -> column + "::date";
                default -> "(" + column + " AT TIME ZONE 'UTC')::date";
            };
        }
        return "timestamp".equals(type) ? "(" + column + " AT TIME ZONE 'UTC')" : column;
    }

    private static boolean temporal(String type) {
        return "date".equals(type) || "timestamp".equals(type) || "timestamptz".equals(type);
    }
}
