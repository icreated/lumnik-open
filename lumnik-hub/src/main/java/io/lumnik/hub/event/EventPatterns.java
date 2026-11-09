package io.lumnik.hub.event;

import io.lumnik.common.text.Text;

/**
 * The one contract of an event-type pattern, shared by mail rules, webhook subscriptions and the
 * row-event gate: an exact type, or a prefix ending in {@code .*}. {@code prefix.*} reaches
 * {@code prefix.} followed by something — never a type that only starts with the letters of
 * {@code prefix}. A {@code *} anywhere else is not a wildcard; the write doors refuse it
 * ({@link #refusal}) so it cannot sit in a rule that never matches.
 */
public final class EventPatterns {

    private static final String SUFFIX = ".*";

    private EventPatterns() {}

    public static boolean matches(String pattern, String eventType) {
        if (pattern == null || eventType == null) return false;
        if (pattern.equals(eventType)) return true;
        if (!pattern.endsWith(SUFFIX)) return false;
        int dotted = pattern.length() - 1;   // length of "prefix."
        return eventType.length() > dotted && eventType.regionMatches(0, pattern, 0, dotted);
    }

    /** Why this pattern cannot be stored, or {@code null} when it is an accepted form. */
    public static String refusal(String pattern) {
        if (Text.blank(pattern)) return "an event pattern is empty";
        int star = pattern.indexOf('*');
        if (star < 0) return null;
        boolean finalDotStar = pattern.endsWith(SUFFIX) && star == pattern.length() - 1 && star > 1;
        if (finalDotStar) return null;
        return "event pattern '" + pattern + "' is not accepted: '*' is only allowed as a final '.*'."
            + " Use an exact event type, or a prefix ending in .* (prefix.*)";
    }
}
