package io.lumnik.hub.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared rule for reading a timestamp written as text, tested where it lives.
 *
 * <p>It was written for the view filter and moved here when ingestion needed the identical one:
 * a value promoted into a timestamp column and a filter typed against that column must resolve to
 * the same instant. {@code ViewFilterTest} keeps what is genuinely the view's — its
 * {@code TEMPORAL_SHAPE} regex, and the guard that the regex and this parser never disagree.
 */
class TemporalTextTest {

    @Test
    void coversTheThreeIsoShapes() {
        assertThat(TemporalText.parse("2026-08-01"))
            .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));      // date-only = midnight UTC
        assertThat(TemporalText.parse("2026-08-01T12:30:00Z"))
            .isEqualTo(Instant.parse("2026-08-01T12:30:00Z"));
        assertThat(TemporalText.parse("2026-08-01 10:04:56.019386"))    // PG timestamp text (space)
            .isEqualTo(Instant.parse("2026-08-01T10:04:56.019386Z"));
        assertThat(TemporalText.parse("not-a-date")).isNull();
        assertThat(TemporalText.parse("2026-02-30")).isNull();          // Feb 30 refused by parse
    }

    /**
     * A naive local date-time reads as UTC — the finding that moved this rule here. Kept next to
     * the shapes above rather than only in the writer's tests: it is a property of the rule, not
     * of the caller that first needed it.
     */
    @Test
    void aNaiveDateTimeIsReadAsUtc() {
        assertThat(TemporalText.parse("2008-04-18T00:00:00"))
            .isEqualTo(Instant.parse("2008-04-18T00:00:00Z"));
        assertThat(TemporalText.parse("2008-04-18 00:00:00"))
            .isEqualTo(Instant.parse("2008-04-18T00:00:00Z"));
    }

    /**
     * Each shape must reach its own parser, and the classification that routes them is a char scan
     * past the {@code T} — so the cases that matter are the ones where a character means two
     * things. A date's hyphens are not a zone; a short {@code -05} after the time is.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "2008-04-18T00:00:00,             2008-04-18T00:00:00Z",   // naive: no zone past the T
        "2008-04-18,                      2008-04-18T00:00:00Z",   // date only: its hyphens are not an offset
        "2026-08-01 10:04:56.019386,      2026-08-01T10:04:56.019386Z",  // naive, fractional, space-separated
        "2026-08-01T12:30:00Z,            2026-08-01T12:30:00Z",
        "2026-08-01T14:30:00+02:00,       2026-08-01T12:30:00Z",
        "2026-08-01T12:30:00+00,          2026-08-01T12:30:00Z",   // short offset, padded
        "2026-08-01T10:04:56-05,          2026-08-01T15:04:56Z",   // short NEGATIVE offset — the trap
        "2026-07-05 10:04:56.019386+05:30, 2026-07-05T04:34:56.019386Z",
    })
    void eachShapeReachesItsOwnParser(String raw, String expected) {
        assertThat(TemporalText.parse(raw)).isEqualTo(Instant.parse(expected));
    }

    @Test
    void nullAndBlankAreNotTimestamps() {
        assertThat(TemporalText.parse(null)).isNull();
        assertThat(TemporalText.parse("")).isNull();
        assertThat(TemporalText.parse("   ")).isNull();
    }
}
