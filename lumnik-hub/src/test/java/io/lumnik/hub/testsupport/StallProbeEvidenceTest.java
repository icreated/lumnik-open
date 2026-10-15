package io.lumnik.hub.testsupport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StallProbeEvidenceTest {

    @Test
    void aFailedWaitLogsTheCallersEvidenceAndStillRethrowsTheOriginalFailure() {
        TimeoutException original = new TimeoutException("never held");

        List<LogProbe.Line> lines = LogProbe.capture(StallProbe.class, () ->
            assertThatThrownBy(() -> StallProbe.diagnosing("the relay", () -> "relay: held=false upstream=7", () -> {
                throw original;
            })).isSameAs(original));

        assertThat(lines).extracting(LogProbe.Line::message)
            .anyMatch(m -> m.contains("the relay did not complete") && m.contains("relay: held=false upstream=7"));
    }

    @Test
    void evidenceThatItselfFailsNeverReplacesTheFailureItWasCalledToExplain() {
        TimeoutException original = new TimeoutException("never held");

        List<LogProbe.Line> lines = LogProbe.capture(StallProbe.class, () ->
            assertThatThrownBy(() -> StallProbe.diagnosing("the relay", () -> {
                throw new IllegalStateException("evidence broke");
            }, () -> {
                throw original;
            })).isSameAs(original));

        assertThat(lines).extracting(LogProbe.Line::message)
            .anyMatch(m -> m.contains("the evidence itself failed") && m.contains("evidence broke"));
    }

    @Test
    void aWaitThatSucceedsLogsNothingAndNeverAsksForEvidence() throws Exception {
        List<LogProbe.Line> lines = LogProbe.capture(StallProbe.class, () -> {
            try {
                assertThat(StallProbe.<String>diagnosing("w", () -> {
                    throw new AssertionError("evidence must not be read on success");
                }, () -> "ok")).isEqualTo("ok");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(lines).isEmpty();
    }
}
