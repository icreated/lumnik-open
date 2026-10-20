package io.lumnik.hub.connector.mapping.writer;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A source date and time written with no time zone, as the jdbc reader read it (#1093): the
 * driver builds it in the hub JVM's zone, so as a plain {@link Timestamp} it is taken for an
 * instant that depends on where the hub runs — 23:30 landed 23:30Z on a UTC hub, 21:30Z on a
 * Paris one.
 *
 * <p>It IS a Timestamp, with the same time and nanos, on purpose: the cursor, persisted as
 * {@code toString()}'s text and bound back as a Timestamp, must not move.
 * {@link DynamicTableWriter#bindable} reads the mark: into a TIMESTAMPTZ column the wall-clock time
 * is stored in the zone the connection declares (#1328), UTC when it declares none, as a parse-date
 * time without a zone is (#1092). The row hash reads it too ({@link RowHasher}), and moves only
 * when that zone is not UTC: see {@link #hashed}.
 */
public final class WallClockTimestamp extends Timestamp {

    private final ZoneId zone;

    private WallClockTimestamp(Timestamp read, ZoneId zone) {
        super(read.getTime());
        setNanos(read.getNanos());
        this.zone = zone.normalized();
    }

    public static WallClockTimestamp of(Timestamp read, ZoneId zone) {
        return new WallClockTimestamp(read, zone);
    }

    /** The instant the source meant: the wall-clock time, in the declared zone. */
    OffsetDateTime instant() {
        return toLocalDateTime().atZone(zone).toInstant().atOffset(ZoneOffset.UTC);
    }

    /**
     * What the row hash reads, and nothing else: every other writer gets the plain Timestamp. In
     * UTC, the Timestamp's own value, so a table written without a zone keeps every hash (and,
     * as before, a hub moved to another JVM zone rewrites it). In another zone, the instant: the
     * value differs, so a re-read rewrites the row where it stood (#1328).
     */
    Object hashed() {
        return zone.equals(ZoneOffset.UTC) ? getTime() : instant().toString();
    }
}
