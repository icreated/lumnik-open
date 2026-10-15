package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reaches a TIMESTAMPTZ column must carry its offset (#1092). pgJDBC sends a LocalDateTime as
 * a `timestamp`, and PostgreSQL places it in the session zone — the hub JVM's: the same 23:30
 * landed 23:30Z on a UTC hub, 21:30Z on a Paris one, 03:30Z next day on a New York one. An
 * OffsetDateTime lands the same everywhere (measured with pgJDBC 42.7.8, JVM in all three zones).
 */
class TimestampBindingTest {

    private static final LocalDateTime WALL_CLOCK = LocalDateTime.parse("2026-08-02T23:30:00");

    @Test
    void a_time_without_an_offset_is_bound_as_utc_into_a_timestamptz_column() {
        assertThat(DynamicTableWriter.bindable(WALL_CLOCK, "TIMESTAMPTZ"))
            .isEqualTo(OffsetDateTime.parse("2026-08-02T23:30:00Z"));
    }

    /** Into a text column (a first chunk that mixed dates and times) the time stays as it was:
     *  an OffsetDateTime there would be rendered in the session zone — "2026-08-03 01:30:00+02"
     *  on a Paris hub (Copilot, #1094). */
    @Test
    void a_time_without_an_offset_is_left_as_it_is_for_any_other_column() {
        assertThat(DynamicTableWriter.bindable(WALL_CLOCK, "TEXT")).isSameAs(WALL_CLOCK);
    }

    @Test
    void values_that_already_carry_their_instant_or_are_dates_keep_it() {
        OffsetDateTime paris = OffsetDateTime.parse("2026-08-02T23:30:00+02:00");
        assertThat(DynamicTableWriter.bindable(paris, "TIMESTAMPTZ")).isSameAs(paris);
        assertThat(DynamicTableWriter.bindable(Instant.parse("2026-08-02T21:30:00Z"), "TIMESTAMPTZ"))
            .isEqualTo(OffsetDateTime.parse("2026-08-02T21:30:00Z"));
        assertThat(DynamicTableWriter.bindable(LocalDate.parse("2026-08-02"), "DATE"))
            .isEqualTo(LocalDate.parse("2026-08-02"));
    }
}
