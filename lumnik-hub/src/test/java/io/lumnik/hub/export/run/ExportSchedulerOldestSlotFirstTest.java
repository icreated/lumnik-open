package io.lumnik.hub.export.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #897: scheduled exports share a few run permits, so the order a tick tries them in decides who
 * waits. An export due every minute, listed first, must not take the only permit ahead of a nightly
 * one owed since 02:00 — tick after tick it would, until the nightly slot is replaced by the next.
 */
class ExportSchedulerOldestSlotFirstTest {

    private static final Instant CREATED = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2099-01-02T02:03:30Z");

    @Test
    void with_one_permit_the_oldest_owed_slot_starts_first() {
        ExportScheduler scheduler = new ExportScheduler() {
            @Override
            List<ScheduledExport> scheduled() {
                return List.of(
                    new ScheduledExport(1L, "every-minute", "* * * * *", CREATED),  // owes 02:03
                    new ScheduledExport(1L, "nightly", "0 2 * * *", CREATED));       // owes 02:00
            }

            @Override
            boolean occurrenceExists(ScheduledExport e, Instant occurrence) {
                return false;
            }
        };
        scheduler.maxConcurrentScheduledRuns = 1;
        scheduler.init();
        List<String> launched = new ArrayList<>();
        scheduler.runner = mock(ExportRunner.class);
        when(scheduler.runner.launch(any(ExportRunRequest.class))).thenAnswer(call -> {
            launched.add(call.<ExportRunRequest>getArgument(0).exportName());
            // A run still going: its permit stays taken for the rest of the tick.
            return new ExportRunner.Launch(new StartResult.Started(UUID.randomUUID()), new CompletableFuture<>());
        });

        assertThat(scheduler.tick(NOW)).isEqualTo(1);
        assertThat(launched).containsExactly("nightly");
    }
}
