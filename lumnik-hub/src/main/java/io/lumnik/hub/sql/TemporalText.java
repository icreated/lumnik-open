package io.lumnik.hub.sql;

import java.math.BigDecimal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * How this platform reads a timestamp written as text — one rule, for every door that has to —
 * and how it writes one ({@link #render}).
 *
 * <p>It was written for the view filter, where the reason to accept everything was that the SQL
 * guard and the in-memory evaluator must never disagree on a row. The ingestion side needs the
 * same rule for a harder reason: the two meet on the same column. A run promotes
 * {@code ordered_at} from an ERP's {@code 2008-04-18T00:00:00}, and an operator later filters
 * {@code ordered_at > 2008-04-18T00:00:00}. If the write side and the read side resolve that text
 * to different instants, the operator filters on the value shown on screen and gets nothing back —
 * a silence with no error anywhere to explain it.
 *
 * <p>Naive forms are read as UTC. That is a choice, and it is the one the platform already states
 * everywhere else it converts: {@code from_epoch} documents "→ timestamp (UTC)", and the date-only
 * rung below has meant midnight UTC since the filter was written. The view's SQL reads them the
 * same way ({@link #utcSession}, #1304). Refusing them instead would be
 * defensible in isolation and wrong here — it would leave the most common ERP export unreadable
 * while the very same string stays filterable.
 */
public final class TemporalText {

    private TemporalText() {}

    /**
     * The text a row value is shown as, null staying null. A timestamp is its UTC instant
     * ({@code 2026-07-01T00:00:00Z}, microseconds kept): its own {@code toString()} is the wall
     * clock in the hub JVM's zone with no offset (#1303, #1310). A date keeps its {@code yyyy-MM-dd};
     * anything else is {@code String.valueOf}.
     */
    public static String render(Object value) {
        return switch (value) {
            case null -> null;
            case Timestamp t -> t.toInstant().toString();
            default -> String.valueOf(value);
        };
    }

    /**
     * The value a row cell is written into JSON as: a timestamp is the text {@link #render} gives it,
     * every other value is left for the serializer, so numbers and booleans stay JSON scalars.
     * Jackson would write a timestamp as {@code 2026-07-01T00:00:00.000+00:00}, milliseconds only,
     * or as epoch millis without the Quarkus defaults (#1314).
     */
    public static Object wire(Object value) {
        return value instanceof Timestamp t ? render(t) : value;
    }

    /**
     * The value an entity's cell is written into JSON as (#1338): a timestamp as {@link #wire}, and a number as
     * its text too — an entity says every value as a string (docs/entities.md), and a fused number now keeps its
     * type in the view only so that it orders and compares as one. A decimal is fused without trailing zeros, so
     * its plain text is {@code 999.5}, never {@code 9.995E+2}; a bigint past 2^53 keeps every digit.
     */
    public static Object entityWire(Object value) {
        return switch (value) {
            case BigDecimal d -> d.toPlainString();
            case Number n -> String.valueOf(n);
            case null, default -> wire(value);
        };
    }

    /**
     * The same rule on the database's side, for one transaction. PostgreSQL reads a naive date or time
     * (a filter's operand, a literal in Ask's SQL, a date cast to a timestamp) and writes a timestamp as
     * text in the session's TimeZone, which pgjdbc takes from the hub JVM's zone. Every read that shows
     * or compares dates sets UTC (#1304, #1325) — the readonly readers through
     * {@link TenantRlsScope#applyTo} — so the same filter picks the same rows and the same value reads
     * the same text whatever zone the hub runs in. {@code SET LOCAL}: bound to the transaction like the
     * tenant, never left on the pooled connection — so it needs one.
     */
    public static void utcSession(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL TimeZone = 'UTC'");
        }
    }

    /**
     * ISO date / datetime (T or space) / offset datetime → Instant; date-only = midnight UTC.
     * Returns {@code null} when the text is not a timestamp at all — callers decide whether that
     * is a refusal (ingestion) or simply "not a temporal operand" (a filter).
     */
    public static Instant parse(String s) {
        if (s == null) return null;
        // Normalize the space date/time separator, the first of two PG-isms vs strict ISO.
        String t = s.strip().replace(' ', 'T');
        int timeAt = t.indexOf('T');
        try {
            if (timeAt < 0) return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!hasZone(t, timeAt))
                return LocalDateTime.parse(t).atOffset(ZoneOffset.UTC).toInstant();
            // The second PG-ism: a whole-hour offset rendered as "+00" where ISO wants "+00:00".
            // Padded only past the 'T' — on a plain date, "-01" is a day of month, not a zone.
            return OffsetDateTime.parse(t.replaceAll("([+-][0-9]{2})$", "$1:00")).toInstant();
        } catch (DateTimeParseException notATimestamp) {
            return null;
        }
    }

    /**
     * Whether the time part carries a zone — the classification that lets each shape reach its own
     * parser directly. Unambiguous because it starts past the {@code T}: a time has no sign of its
     * own, so a {@code +} or {@code -} there can only open an offset, while the hyphens of the date
     * are already behind us.
     *
     * <p>This exists for speed, and the shape of the speed was not the expected one. The ladder
     * this replaced tried {@code OffsetDateTime.parse} first and caught its refusal, which made
     * the throw part of the SUCCESS path for every naive ERP timestamp — the common case in
     * ingestion, run once per row. Measured on 21.0.10, best of seven, 150 000 values per pass:
     *
     * <pre>
     *   naive "2008-04-18T00:00:00"    1427 ns -> 369 ns   (3.9x)
     *   date only "2008-04-18"         1481 ns -> 121 ns   (12.2x, it threw twice)
     *   with offset                     785 ns -> 783 ns   (unchanged: it already hit first)
     * </pre>
     *
     * <p>The obvious alternative — {@code ISO_DATE_TIME.parseBest}, which resolves offset-or-not
     * without throwing — was measured too and is SLOWER than the exception it removes: 0.68x on
     * the naive shape, 0.88x with an offset. Avoiding a throw is only worth what the avoidance
     * costs, and this one costs a char scan.
     */
    private static boolean hasZone(String t, int timeAt) {
        for (int i = timeAt + 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 'Z' || c == '+' || c == '-') return true;
        }
        return false;
    }
}
