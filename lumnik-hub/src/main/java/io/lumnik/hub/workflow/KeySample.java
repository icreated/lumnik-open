package io.lumnik.hub.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * How many documents a sweep could not read, and the first few of them (#1588): enough for a diagnostic to name
 * where to look, while the hub's heap does not grow with the table.
 */
final class KeySample {

    private static final int SHOWN = 5;

    private final List<String> first = new ArrayList<>(SHOWN);
    private long count;

    void add(String key) {
        if (first.size() < SHOWN) first.add(key);
        count++;
    }

    long count() {
        return count;
    }

    boolean isEmpty() {
        return count == 0;
    }

    /** The first keys seen, joined for a log line. */
    String shown() {
        return String.join(", ", first);
    }
}
