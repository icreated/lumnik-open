package io.lumnik.hub.connector.runtime;

import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P10 — the execution window, the DBA's lever: {@code spec.window: "HH:MM-HH:MM"} (UTC,
 * the scheduler's clock). Cron slots due outside the window are skipped, never queued;
 * a manual {@code lm connector run} bypasses it (an explicit operator act). One class is
 * the single truth for the apply-time validation AND the scheduler gate.
 *
 * <p>Failure postures, deliberately asymmetric: an unparseable STORED window fails
 * <b>open</b> at the scheduler (no fence — a typo must never silently stop ingestion
 * forever), while validation at apply refuses it with the reason named — the mirror of
 * {@code isCronDue}'s garbage-cron-is-never-due posture.
 */
public final class RunWindow {

    private static final Pattern SPEC = Pattern.compile("^(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})$");

    private RunWindow() {}

    /** Start inclusive, end exclusive; an end at or before the start wraps past midnight. */
    public static boolean contains(String spec, LocalTime now) {
        if (spec == null || spec.isBlank()) return true;
        LocalTime[] w = parse(spec);
        if (w == null) return true; // fail open — see class javadoc
        LocalTime start = w[0], end = w[1];
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end); // overnight
    }

    /** Empty when valid; otherwise the reason, named (the validators' Optional contract). */
    public static Optional<String> validate(String spec) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        LocalTime[] w = parse(spec);
        if (w == null) return Optional.of("invalid window '" + spec + "' — expected HH:MM-HH:MM (UTC), e.g. 22:00-06:00");
        if (w[0].equals(w[1])) return Optional.of("empty window '" + spec + "' — start and end are equal");
        return Optional.empty();
    }

    private static LocalTime[] parse(String spec) {
        Matcher m = SPEC.matcher(spec.trim());
        if (!m.matches()) return null;
        try {
            return new LocalTime[]{
                LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))),
                LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)))};
        } catch (RuntimeException e) {
            return null; // 25:00 etc.
        }
    }
}
