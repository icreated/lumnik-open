package io.lumnik.hub.connector.mapping.writer;

import java.sql.Timestamp;

/**
 * A source date and time written with no time zone, as the jdbc reader read it (#1093): the
 * driver builds it in the hub JVM's zone, so as a plain {@link Timestamp} it is taken for an
 * instant that depends on where the hub runs — 23:30 landed 23:30Z on a UTC hub, 21:30Z on a
 * Paris one.
 *
 * <p>It IS a Timestamp, with the same time and nanos, on purpose: everything a hub table already
 * holds is derived from that value and must not move — the row hash (a new hash is a new row
 * version), and the cursor, persisted as {@code toString()}'s text and bound back as a Timestamp.
 * Only {@link DynamicTableWriter#bindable} reads the mark: into a TIMESTAMPTZ column the wall-clock
 * time is stored as UTC, as a parse-date time without a zone is (#1092).
 */
public final class WallClockTimestamp extends Timestamp {

    private WallClockTimestamp(Timestamp read) {
        super(read.getTime());
        setNanos(read.getNanos());
    }

    public static WallClockTimestamp of(Timestamp read) {
        return new WallClockTimestamp(read);
    }
}
