package io.lumnik.hub.connector.rest;

import java.util.regex.Pattern;

/**
 * A single bounded, single-line excerpt of a body/error string for failure messages and
 * dead-letter records. Collapses whitespace (so pretty-printed XML/JSON newlines don't bloat a
 * log line) and caps the length. Shared by the REST/GraphQL/SOAP validators and parsers so the
 * "don't dump a huge response into the logs" policy lives in exactly one place.
 */
public final class Snippets {

    private static final int MAX = 200;
    // We emit at most MAX chars and whitespace-collapse only ever shrinks, so scanning far past MAX
    // is wasted regex work on a huge response body (the very case this guards the logs from). Inspect
    // a bounded head window instead — generous enough that normal inputs are untouched.
    private static final int RAW_WINDOW = MAX * 8;
    private static final Pattern WS = Pattern.compile("\\s+");

    private Snippets() {}

    /** A trimmed, whitespace-collapsed excerpt capped at {@value #MAX} chars (then a {@code …}). */
    public static String bound(String s) {
        if (s == null) return "";
        String head = s.length() > RAW_WINDOW ? s.substring(0, RAW_WINDOW) : s;
        String collapsed = WS.matcher(head.strip()).replaceAll(" ");
        return collapsed.length() <= MAX ? collapsed : collapsed.substring(0, MAX) + "…";
    }
}
