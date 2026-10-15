package io.lumnik.testkit.search.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** The three scored query classes of the #675 corpus, plus the asserted-not-scored isolation class. */
public enum QueryClass {
    EXACT_TOKEN("exact-token"),
    PARAPHRASE("paraphrase"),
    MIXED("mixed"),
    ISOLATION("isolation");

    private final String label;

    QueryClass(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static QueryClass fromLabel(String label) {
        return Arrays.stream(values()).filter(c -> c.label.equals(label)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown query class: " + label));
    }

    /** Isolation queries are asserted (leak / control), never folded into relevance metrics. */
    public boolean scored() {
        return this != ISOLATION;
    }
}
