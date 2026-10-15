package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.jdbc.JdbcCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source time without a zone, as the jdbc reader marks it (#1093). The mark must change nothing a
 * hub table already holds — the row hash (a new hash is a new row version) and the persisted
 * cursor text — and one thing only: a TIMESTAMPTZ column stores the wall-clock time as UTC, not as
 * the hub JVM's zone. Run in New York so a UTC machine cannot hide the difference.
 */
class WallClockTimestampTest {

    private TimeZone saved;

    @BeforeEach
    void inNewYork() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(saved);
    }

    private static Timestamp asTheDriverReadsIt() {
        return Timestamp.valueOf(LocalDateTime.parse("2026-08-02T23:30:00"));
    }

    @Test
    void the_row_hash_is_the_one_a_plain_timestamp_had() {
        RowHasher hasher = new RowHasher();
        Timestamp plain = asTheDriverReadsIt();
        assertThat(hasher.hash(Map.of("t", WallClockTimestamp.of(plain))))
            .isEqualTo(hasher.hash(Map.of("t", plain)));
    }

    @Test
    void the_persisted_cursor_is_the_one_a_plain_timestamp_wrote() {
        Timestamp plain = asTheDriverReadsIt();
        WallClockTimestamp marked = WallClockTimestamp.of(plain);
        assertThat(new JdbcCursor("t", marked.toString(), Map.of("t", marked)).toConnectorCursor().value())
            .isEqualTo(new JdbcCursor("t", plain.toString(), Map.of("t", plain)).toConnectorCursor().value());
    }

    @Test
    void a_timestamptz_column_stores_the_wall_clock_as_utc() {
        assertThat(DynamicTableWriter.bindable(WallClockTimestamp.of(asTheDriverReadsIt()), "TIMESTAMPTZ"))
            .isEqualTo(OffsetDateTime.parse("2026-08-02T23:30:00Z"));
    }

    @Test
    void an_unmarked_timestamp_is_an_instant_and_binds_as_before() {
        Timestamp instant = asTheDriverReadsIt();
        assertThat(DynamicTableWriter.bindable(instant, "TIMESTAMPTZ")).isSameAs(instant);
    }

    @Test
    void any_other_column_gets_the_value_as_before() {
        WallClockTimestamp marked = WallClockTimestamp.of(asTheDriverReadsIt());
        assertThat(DynamicTableWriter.bindable(marked, "TEXT")).isSameAs(marked);
    }
}
