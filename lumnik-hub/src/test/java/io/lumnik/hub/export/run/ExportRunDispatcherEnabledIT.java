package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.runtime.RunQueueEnabledProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The same dispatcher with {@code lumnik.run-queue.enabled=true}: a poll and a wake-up both start a queued run. */
@QuarkusTest
@TestProfile(RunQueueEnabledProfile.class)
class ExportRunDispatcherEnabledIT extends ExportQueuedRunITSupport {

    @Test
    void a_poll_with_the_flag_on_starts_a_queued_export_run() throws Exception {
        String name = applyExport(unique("poll") + ".csv", false);
        UUID run = queue(name, Instant.now().minusSeconds(5));

        dispatcher.poll();

        assertThat(settled(run).status()).isEqualTo("Completed");
    }

    @Test
    void a_wake_up_starts_a_queued_export_run_without_waiting_for_a_poll() throws Exception {
        String name = applyExport(unique("wake") + ".csv", false);
        UUID run = queue(name, Instant.now().minusSeconds(5));

        dispatcher.wake();

        assertThat(settled(run).status()).isEqualTo("Completed");
    }
}
