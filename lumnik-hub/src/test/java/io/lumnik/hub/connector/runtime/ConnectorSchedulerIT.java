package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConnectorSchedulerIT {

  @Inject ConnectorScheduler scheduler;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject UserTransaction utx;

  @Test
  void due_connector_with_no_inflight_run_is_listed_and_submitted() {
    // Every-minute cron, last ran 2 minutes ago → due now.
    UUID id = EventItSupport.runInTxAsSystem(utx, () -> {
      UUID cid = newConnector("* * * * *");
      seedRun(cid, "Completed", Instant.now().minusSeconds(120));
      return cid;
    });

    assertThat(scheduler.listDueConnectors().stream().map(ConnectorScheduler.DueConnector::id))
        .as("an idle, due connector must be listed")
        .contains(id);

    assertThat(scheduler.tick())
        .as("tick submits the due connector")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void connector_with_run_in_flight_is_excluded() {
    UUID id = EventItSupport.runInTxAsSystem(utx, () -> {
      UUID cid = newConnector("* * * * *");
      seedRun(cid, "Running", Instant.now().minusSeconds(120));   // in flight
      return cid;
    });

    assertThat(scheduler.listDueConnectors().stream().map(ConnectorScheduler.DueConnector::id))
        .as("a connector with a Running run must NOT be picked up again")
        .doesNotContain(id);
  }

  @Test
  void due_connector_outside_its_window_is_skipped_not_submitted() throws Exception {
    // P10 wiring: due by cron, but the window [now+2h, now+3h) excludes now — the
    // scheduler must skip it (and never queue it). The gate itself is unit-tested in
    // RunWindowTest; THIS pins that tick() actually consults it.
    java.time.LocalTime plus2 = java.time.LocalTime.now(java.time.ZoneOffset.UTC).plusHours(2).withSecond(0).withNano(0);
    String window = "%02d:%02d-%02d:%02d".formatted(
        plus2.getHour(), plus2.getMinute(), plus2.plusHours(1).getHour(), plus2.plusHours(1).getMinute());

    UUID id = EventItSupport.runInTxAsSystem(utx, () -> {
      UUID cid = newConnector("* * * * *");
      connectors.findById(cid).setRunWindow(window); // managed entity, same tx — dirty-checked
      seedRun(cid, "Completed", Instant.now().minusSeconds(120));
      return cid;
    });

    var due = scheduler.listDueConnectors().stream()
        .filter(d -> d.id().equals(id)).findFirst();
    assertThat(due).as("the window never filters the LISTING (SQL) — only the gate").isPresent();
    assertThat(due.get().window()).isEqualTo(window);

    long runsBefore = countRuns(id);
    scheduler.tick();
    Thread.sleep(1500); // submitAsync would have created the run row by now
    assertThat(countRuns(id))
        .as("a due-but-outside-window connector must not gain a run")
        .isEqualTo(runsBefore);
  }

  private long countRuns(UUID connectorId) {
    return EventItSupport.runInTxAsSystem(utx, () ->
        runs.count("connectorId = ?1", connectorId));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private UUID newConnector(String cron) {
    Connector c = new Connector();
    c.setTenantId(1L);
    c.setName("scheduled-" + System.nanoTime());
    c.setTypeId("hello-world");
    c.setConfig(Map.of("count", 1));
    c.setSchedule(cron);
    c.setEnabled(true);
    connectors.persist(c);
    connectors.flush();
    return c.getId();
  }

  private void seedRun(UUID connectorId, String status, Instant startedAt) {
    ConnectorRun r = new ConnectorRun();
    r.setTenantId(1L);
    r.setConnectorId(connectorId);
    r.setStatus(status);
    r.setSyncMode("FullRefresh");
    r.setStartedAt(startedAt);
    r.setTriggeredBy("SCHEDULE");
    runs.persist(r);
    runs.flush();
  }
}
