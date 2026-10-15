package io.lumnik.hub.api.connector;

import io.lumnik.hub.api.run.RunAdminDto;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** #952: a Queued run has no start. The summary readers say "not started" (null), never the accept instant, never an NPE. */
class ConnectorAdminDtoTest {

    private static ConnectorRun queued() {
        ConnectorRun r = new ConnectorRun();
        r.setStatus("Queued");
        r.setStartedAt(null);
        r.setQueuedAt(Instant.parse("2026-10-12T10:00:00Z"));
        return r;
    }

    @Test
    void a_queued_last_run_reports_its_status_and_no_last_run_time() {
        ConnectorAdminDto dto = ConnectorAdminDto.fromWithRun(new Connector(), queued());
        assertThat(dto.status()).isEqualTo("Queued");
        assertThat(dto.lastRunAt()).as("it has not run: the accept instant is not a run time").isNull();
    }

    @Test
    void a_queued_run_lists_without_a_start() {
        RunAdminDto dto = RunAdminDto.from(queued(), 0, "c", "e");
        assertThat(dto.status()).isEqualTo("Queued");
        assertThat(dto.startedAt()).isNull();
        assertThat(dto.endedAt()).isNull();
    }
}
