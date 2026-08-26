package io.lumnik.hub.connector.runtime;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10: the DBA's lever — `spec.window: "HH:MM-HH:MM"` (UTC, the scheduler's clock).
 * Cron slots outside the window are skipped, never queued; no window = always runnable.
 * One truth for both the apply-time validation and the scheduler gate.
 */
class RunWindowTest {

    @Test
    void no_window_means_always_runnable() {
        assertThat(RunWindow.contains(null, LocalTime.of(12, 0))).isTrue();
        assertThat(RunWindow.contains("", LocalTime.of(12, 0))).isTrue();
        assertThat(RunWindow.contains("  ", LocalTime.of(12, 0))).isTrue();
    }

    @Test
    void same_day_window_start_inclusive_end_exclusive() {
        assertThat(RunWindow.contains("08:00-17:00", LocalTime.of(8, 0))).isTrue();
        assertThat(RunWindow.contains("08:00-17:00", LocalTime.of(12, 30))).isTrue();
        assertThat(RunWindow.contains("08:00-17:00", LocalTime.of(17, 0))).isFalse();
        assertThat(RunWindow.contains("08:00-17:00", LocalTime.of(7, 59))).isFalse();
    }

    @Test
    void overnight_window_crosses_midnight() {
        // THE canonical case: don't touch the ERP during business hours.
        assertThat(RunWindow.contains("22:00-06:00", LocalTime.of(23, 0))).isTrue();
        assertThat(RunWindow.contains("22:00-06:00", LocalTime.of(2, 0))).isTrue();
        assertThat(RunWindow.contains("22:00-06:00", LocalTime.of(22, 0))).isTrue();
        assertThat(RunWindow.contains("22:00-06:00", LocalTime.of(6, 0))).isFalse();
        assertThat(RunWindow.contains("22:00-06:00", LocalTime.of(12, 0))).isFalse();
    }

    @Test
    void validation_names_the_reason() {
        assertThat(RunWindow.validate("22:00-06:00")).isEmpty();
        assertThat(RunWindow.validate("8:00-17:00")).isPresent();   // HH:MM strict
        assertThat(RunWindow.validate("22:00")).isPresent();
        assertThat(RunWindow.validate("25:00-06:00")).isPresent();
        assertThat(RunWindow.validate("22:00-22:00")).isPresent();  // empty window = surely a mistake
        assertThat(RunWindow.validate("nonsense")).isPresent();
    }

    @Test
    void an_unparseable_stored_window_never_blocks_the_scheduler() {
        // Defense in depth: validation refuses these at apply, but a corrupted stored value
        // must fail OPEN at the scheduler (run anyway) — a typo must not silently stop
        // ingestion forever. Mirrors isCronDue's not-due-on-garbage posture, inverted:
        // garbage cron = never due (nothing spins); garbage window = no fence (data flows).
        assertThat(RunWindow.contains("garbage", LocalTime.of(12, 0))).isTrue();
    }
}
