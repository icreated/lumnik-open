package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day two runs stayed {@code Running} for ever, neither the run id, nor the endpoint, nor the
 * moment were anywhere an operator reading {@code docker logs} could find them: a normal run wrote
 * nothing there. The events speak to machines; these two lines speak to the human.
 */
@QuarkusTest
class RunLifecycleLogIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;

  private record Line(Level level, String message) {}

  @Test
  void a_completed_run_writes_a_start_line_and_an_end_line_with_its_counters() {
    UUID connectorId = inTx(() -> connector("hello-world", Map.of("count", 5)).getId());

    List<Line> lines = capture(() -> engine.runSync(connectorId, new RunTrigger.Manual(1L)));

    UUID runId = inTx(() -> runs.findRecentActiveByConnector(connectorId, 1).get(0).getId());
    assertThat(lines).as("one start line naming the run")
        .anySatisfy(l -> {
          assertThat(l.level()).isEqualTo(Level.INFO);
          assertThat(l.message()).contains("run " + runId + " started").contains("hello-world");
        });
    assertThat(lines).as("one end line with the status, the counters and the duration in ms")
        .anySatisfy(l -> {
          assertThat(l.level()).isEqualTo(Level.INFO);
          assertThat(l.message()).contains("run " + runId + " Completed").contains("in=5").contains("out=5").contains(" ms");
        });
  }

  @Test
  void a_failed_run_ends_on_a_warning_that_carries_the_reason() {
    // registry.find throws before the first chunk: the cheapest deterministic failure (no socket)
    UUID connectorId = inTx(() -> connector("no-such-connector-type", Map.of()).getId());

    List<Line> lines = capture(() -> engine.runSync(connectorId, new RunTrigger.Manual(1L)));

    UUID runId = inTx(() -> runs.findRecentActiveByConnector(connectorId, 1).get(0).getId());
    assertThat(lines).as("a failed run ends on a WARN naming the run and the reason")
        .anySatisfy(l -> {
          assertThat(l.level()).isEqualTo(Level.WARNING);
          assertThat(l.message()).contains("run " + runId + " Failed").contains("no-such-connector-type");
        });
  }

  /** Runs {@code action} with a probe on the engine's logger; returns what it wrote. */
  private static List<Line> capture(Runnable action) {
    List<Line> lines = new CopyOnWriteArrayList<>();
    Logger log = Logger.getLogger(ConnectorEngine.class.getName());
    Handler probe = new Handler() {
      @Override public void publish(LogRecord r) {
        if (!(r instanceof ExtLogRecord ext)) {
          throw new AssertionError("the probe needs jboss-logmanager records (formatted messages); got " + r.getClass());
        }
        lines.add(new Line(r.getLevel(), ext.getFormattedMessage()));
      }
      @Override public void flush() {}
      @Override public void close() {}
    };
    log.addHandler(probe);
    try {
      action.run();
    } finally {
      log.removeHandler(probe);
    }
    return lines;
  }

  private Connector connector(String typeId, Map<String, Object> config) {
    Connector c = new Connector();
    c.setTenantId(1L);
    c.setName("lifecycle-log-" + System.nanoTime());
    c.setTypeId(typeId);
    c.setConfig(config);
    connectors.persist(c);
    connectors.flush();
    return c;
  }

  private static <T> T inTx(Supplier<T> body) {
    return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(body::get));
  }
}
