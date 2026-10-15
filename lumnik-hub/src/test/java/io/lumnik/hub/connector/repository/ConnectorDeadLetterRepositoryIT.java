package io.lumnik.hub.connector.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.runtime.DeadLetterCapture;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ConnectorDeadLetterRepositoryIT {

  @Inject ConnectorDeadLetterRepository repo;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject DeadLetterCapture capture;
  @Inject UserTransaction utx;

  @Test
  void countByRun_excludes_benign_findByRun_returns_all() throws Exception {
    UUID[] ids = seedConnectorAndRun();
    UUID connectorId = ids[0];
    UUID runId = ids[1];
    var empty = JsonNodeFactory.instance.objectNode();

    TenantContext.callAsSystem(() -> {
      capture.capture(connectorId, runId, empty, new SkipReason.ValidationFailed(List.of("amount < 0")));
      capture.capture(connectorId, runId, empty, new SkipReason.Duplicate("BK-1"));
      capture.capture(connectorId, runId, empty, new SkipReason.PolicyExcluded("price-policy"));
      return null;
    });

    assertEquals(1, repo.countByRun(runId), "both benign reasons (Duplicate + PolicyExcluded) must not count as a loss");
    var all = repo.findByRun(runId, 50);
    assertEquals(3, all.size(), "list shows every reason");
    // findByRun orders by capturedAt desc — assert non-increasing (tie-safe vs equal Instant
    // timestamps, which sequential inserts can produce at the DB's resolution).
    for (int i = 1; i < all.size(); i++) {
      assertFalse(all.get(i).getCapturedAt().isAfter(all.get(i - 1).getCapturedAt()),
          "findByRun must be newest-first by capturedAt");
    }
  }

  /**
   * #892: the runs ledger counts a whole page in one query. Each run gets its own count — not the
   * page's total — and a run with no real loss is still answered, with 0, so no caller has to
   * remember that an absent key means zero.
   */
  @Test
  void countByRuns_answers_every_run_asked_with_its_own_real_losses() throws Exception {
    UUID[] a = seedConnectorAndRun();
    UUID[] b = seedConnectorAndRun();
    UUID[] c = seedConnectorAndRun();
    var empty = JsonNodeFactory.instance.objectNode();

    TenantContext.callAsSystem(() -> {
      capture.capture(a[0], a[1], empty, new SkipReason.ValidationFailed(List.of("amount < 0")));
      capture.capture(a[0], a[1], empty, new SkipReason.ValidationFailed(List.of("qty < 0")));
      capture.capture(a[0], a[1], empty, new SkipReason.Duplicate("BK-1"));
      capture.capture(c[0], c[1], empty, new SkipReason.ValidationFailed(List.of("amount < 0")));
      capture.capture(c[0], c[1], empty, new SkipReason.PolicyExcluded("price-policy"));
      return null;
    });

    assertEquals(Map.of(a[1], 2L, b[1], 0L, c[1], 1L), repo.countByRuns(List.of(a[1], b[1], c[1])));
    assertEquals(Map.of(), repo.countByRuns(List.of()), "an empty page asks nothing");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** Returns [connectorId, runId] */
  private UUID[] seedConnectorAndRun() throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("dlq-repo-test-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setTenantId(1L);
        ep.setConnectorId(c.getId());
        ep.setName("default");
        ep.setMethod("GET");
        ep.setPath("/test");
        ep.setEnabled(true);
        ep.setConfig(Map.of("name", "test_table"));
        endpoints.persist(ep);
        endpoints.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(c.getId());
        run.setStatus("Running");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        runs.flush();

        utx.commit();
        return new UUID[]{c.getId(), run.getId()};
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });
  }
}
