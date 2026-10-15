package io.lumnik.hub.testsupport;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Small readings a stalled wait attaches to its report (#761): how loaded the machine was, what a container last said. */
public final class StallEvidence {

    private StallEvidence() {}

    /** The last {@code lines} lines of {@code text}, saying how many came before them. */
    public static String tail(String text, int lines) {
        if (text == null || text.isBlank()) return "(no output)";
        String[] all = text.stripTrailing().split("\n", -1);
        if (all.length <= lines) return String.join("\n", all);
        int dropped = all.length - lines;
        return "(" + dropped + " earlier lines dropped)\n"
            + String.join("\n", Arrays.copyOfRange(all, dropped, all.length));
    }

    /** The one-minute load average of the host, or {@code unavailable} where the JVM cannot read it. */
    public static String hostLoad() {
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return load < 0 ? "unavailable" : String.format(Locale.ROOT, "%.2f", load);
    }
}
