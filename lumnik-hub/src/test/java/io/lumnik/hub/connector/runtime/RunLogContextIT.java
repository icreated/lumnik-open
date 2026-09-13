package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.MDC;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A log line written during a run must say WHICH run it belongs to.
 *
 * <p>The run thread sets its tenant by hand and never touched the MDC, so {@code %X{tenantId}} —
 * already in the console format — printed empty for everything a run emits, and no key carried the
 * run id at all. The sixteen WARNs a reader or a writer can raise mid-run (embedding refused, cursor
 * from another configuration, override query failed…) named neither the run nor the tenant: an
 * operator reading {@code docker logs} on a hub driving several runs could not tell them apart.
 *
 * <p>Asserted here on the run's OWN lifecycle lines rather than by provoking a WARN: they are
 * emitted from the same scope, so what holds for them holds for every line raised under it.
 */
@QuarkusTest
class RunLogContextIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;

  private record Line(String message, String runId, String tenantId) {}

  @Test
  void every_line_a_run_writes_carries_its_run_id_and_tenant() {
    UUID connectorId = inTx(() -> connector().getId());

    List<Line> lines = capture(() -> engine.runSync(connectorId, new RunTrigger.Manual(1L)));

    UUID runId = inTx(() -> runs.findRecentActiveByConnector(connectorId, 1).get(0).getId());

    assertThat(lines).as("the run wrote its lifecycle lines").isNotEmpty();
    assertThat(lines)
        .as("the start line must carry the run id and the tenant in its MDC")
        .anySatisfy(l -> {
          assertThat(l.message()).contains("run " + runId + " started");
          assertThat(l.runId()).isEqualTo(runId.toString());
          assertThat(l.tenantId()).isEqualTo("1");
        });
    assertThat(lines)
        .as("the end line must carry them too — it is the one an operator greps for")
        .anySatisfy(l -> {
          assertThat(l.message()).contains("run " + runId + " Completed");
          assertThat(l.runId()).isEqualTo(runId.toString());
          assertThat(l.tenantId()).isEqualTo("1");
        });
  }

  /**
   * The scope a run opens must give the caller back what it had. The pair this replaced did
   * {@code CURRENT_TENANT.remove()} on the way out, so a synchronous run left its caller with no
   * tenant at all — invisible until the caller's next read answered for nobody.
   */
  @Test
  void a_synchronous_run_gives_the_caller_its_tenant_back() {
    UUID connectorId = inTx(() -> connector().getId());
    TenantContextFilter.CURRENT_TENANT.set(7L);
    try {
      engine.runSync(connectorId, new RunTrigger.Manual(1L));
      assertThat(TenantContextFilter.current())
          .as("a run restores the caller's tenant; it does not remove it")
          .isEqualTo(7L);
    } finally {
      // All THREE, because that is what TenantScope.restore() set on the way out when it found a
      // previous tenant. Clearing only CURRENT_TENANT leaves tenant 7 in CurrentTenantHolder — which
      // is what binds the RLS GUC — and in the MDC, for whatever test runs next on this thread.
      TenantContextFilter.CURRENT_TENANT.remove();
      CurrentTenantHolder.clear();
      MDC.remove("tenantId");
    }
  }

  /** Runs {@code action} with a probe on the engine's logger; returns each line with its MDC. */
  private static List<Line> capture(Runnable action) {
    List<Line> lines = new CopyOnWriteArrayList<>();
    Logger log = Logger.getLogger(ConnectorEngine.class.getName());
    Handler probe = new Handler() {
      @Override public void publish(LogRecord r) {
        if (!(r instanceof ExtLogRecord ext)) {
          throw new AssertionError("the probe needs jboss-logmanager records (MDC); got " + r.getClass());
        }
        lines.add(new Line(ext.getFormattedMessage(), ext.getMdc("runId"), ext.getMdc("tenantId")));
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

  private Connector connector() {
    Connector c = new Connector();
    c.setTenantId(1L);
    c.setName("run-log-context-" + System.nanoTime());
    c.setTypeId("hello-world");
    c.setConfig(Map.of("count", 3));
    connectors.persist(c);
    connectors.flush();
    return c;
  }

  private static <T> T inTx(Supplier<T> body) {
    return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(body::get));
  }
}
