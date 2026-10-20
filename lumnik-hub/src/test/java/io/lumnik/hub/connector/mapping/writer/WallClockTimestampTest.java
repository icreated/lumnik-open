package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lumnik.hub.connector.jdbc.JdbcCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    @ParameterizedTest
    @ValueSource(strings = {"Z", "UTC", "Etc/UTC"})
    void in_utc_however_written_the_row_hash_is_the_one_a_plain_timestamp_had(String utc) {
        RowHasher hasher = new RowHasher();
        Timestamp plain = asTheDriverReadsIt();
        assertThat(hasher.hash(Map.of("t", WallClockTimestamp.of(plain, ZoneId.of(utc)))))
            .isEqualTo(hasher.hash(Map.of("t", plain)));
    }

    @Test
    void the_persisted_cursor_is_the_one_a_plain_timestamp_wrote() {
        Timestamp plain = asTheDriverReadsIt();
        WallClockTimestamp marked = WallClockTimestamp.of(plain, ZoneOffset.UTC);
        assertThat(new JdbcCursor("t", marked.toString(), Map.of("t", marked)).toConnectorCursor().value())
            .isEqualTo(new JdbcCursor("t", plain.toString(), Map.of("t", plain)).toConnectorCursor().value());
    }

    @Test
    void a_timestamptz_column_stores_the_wall_clock_as_utc() {
        assertThat(DynamicTableWriter.bindable(WallClockTimestamp.of(asTheDriverReadsIt(), ZoneOffset.UTC), "TIMESTAMPTZ"))
            .isEqualTo(OffsetDateTime.parse("2026-08-02T23:30:00Z"));
    }

    @Test
    void an_unmarked_timestamp_is_an_instant_and_binds_as_before() {
        Timestamp instant = asTheDriverReadsIt();
        assertThat(DynamicTableWriter.bindable(instant, "TIMESTAMPTZ")).isSameAs(instant);
    }

    @Test
    void any_other_column_gets_the_value_as_before() {
        WallClockTimestamp marked = WallClockTimestamp.of(asTheDriverReadsIt(), ZoneOffset.UTC);
        assertThat(DynamicTableWriter.bindable(marked, "TEXT")).isSameAs(marked);
    }

    // ── #1328: the connection's declared zone ───────────────────────────────

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Test
    void a_declared_zone_reads_the_wall_clock_there_in_summer_and_in_winter() {
        WallClockTimestamp summer = WallClockTimestamp.of(asTheDriverReadsIt(), PARIS);
        WallClockTimestamp winter = WallClockTimestamp.of(Timestamp.valueOf(LocalDateTime.parse("2026-01-15T23:30:00")), PARIS);
        assertThat(DynamicTableWriter.bindable(summer, "TIMESTAMPTZ")).isEqualTo(OffsetDateTime.parse("2026-08-02T21:30:00Z"));
        assertThat(DynamicTableWriter.bindable(winter, "TIMESTAMPTZ")).isEqualTo(OffsetDateTime.parse("2026-01-15T22:30:00Z"));
    }

    @Test
    void another_zone_is_another_row_hash_so_a_reread_rewrites_the_row() {
        RowHasher hasher = new RowHasher();
        Timestamp plain = asTheDriverReadsIt();
        String utc = hasher.hash(Map.of("t", WallClockTimestamp.of(plain, ZoneOffset.UTC)));
        String paris = hasher.hash(Map.of("t", WallClockTimestamp.of(plain, PARIS)));
        assertThat(paris).isNotEqualTo(utc)
            .isEqualTo(hasher.hash(Map.of("t", WallClockTimestamp.of(plain, PARIS))));
    }

    @Test
    void a_declared_zone_leaves_the_persisted_cursor_alone() {
        Timestamp plain = asTheDriverReadsIt();
        WallClockTimestamp marked = WallClockTimestamp.of(plain, PARIS);
        assertThat(new JdbcCursor("t", marked.toString(), Map.of("t", marked)).toConnectorCursor().value())
            .isEqualTo(new JdbcCursor("t", plain.toString(), Map.of("t", plain)).toConnectorCursor().value());
    }

    @Test
    void outside_the_row_hash_a_mark_is_written_as_a_plain_timestamp() {
        // A dead-lettered row's payload goes through the hub's mapper, dates as text: the zone
        // changes the hash, never how the row is written there.
        ObjectMapper hubMapper = new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Timestamp plain = asTheDriverReadsIt();
        JsonNode asPlain = hubMapper.valueToTree(Map.of("t", plain));
        JsonNode inParis = hubMapper.valueToTree(Map.of("t", WallClockTimestamp.of(plain, PARIS)));
        JsonNode inUtc = hubMapper.valueToTree(Map.of("t", WallClockTimestamp.of(plain, ZoneOffset.UTC)));
        assertThat(inParis).isEqualTo(asPlain);
        assertThat(inUtc).isEqualTo(asPlain);
    }
}
