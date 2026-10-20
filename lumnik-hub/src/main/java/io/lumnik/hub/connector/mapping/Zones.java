package io.lumnik.hub.connector.mapping;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * A declared {@code zone} — where a source wrote the times it stores without one: parse-date's
 * param, a jdbc connection's key (#1328). One reading for both: missing, null and blank all mean
 * none, and none reads the times as UTC, never in the zone of the machine the hub runs on.
 */
public final class Zones {

    private Zones() {}

    /** The declared zone, or UTC when there is none. */
    public static ZoneId orUtc(String declared) {
        return none(declared) ? ZoneOffset.UTC : ZoneId.of(declared);
    }

    /** Why {@code declared} is not a zone the hub reads; empty when it is, or when there is none. */
    public static Optional<String> whyNot(String declared) {
        if (none(declared)) return Optional.empty();
        try {
            ZoneId.of(declared);
            return Optional.empty();
        } catch (DateTimeException e) {
            return Optional.of("'zone' is not a time zone: \"" + declared + "\" (for example Europe/Paris or UTC)");
        }
    }

    private static boolean none(String declared) {
        return declared == null || declared.isBlank();
    }
}
