package io.lumnik.hub.api.run;

import io.lumnik.testkit.auth.TestIdentity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.DeadLetterCapture;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class RunAdminResourceTest {

  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorDeadLetterRepository dlq;
  @Inject DeadLetterCapture capture;
  @Inject UserTransaction utx;

  @Test
  void list_returns_recent_runs() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("limit", 10)
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body(notNullValue());
  }

  @Test
  void get_run_exposes_deadLetterCount() throws Exception {
    UUID runId = seedRunWithDeadLetters(2);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(200)
        .body("deadLetterCount", equalTo(2));
  }

  @Test
  void list_exposes_deadLetterCount() throws Exception {
    UUID runId = seedRunWithDeadLetters(2);
    String idStr = runId.toString();

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("limit", 50)
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("findAll { it.id == '" + idStr + "' }.deadLetterCount", hasItem(equalTo(2)));
  }

  /**
   * The run ledger's arithmetic is only honest if every surface shows it —
   * PR #250 made {@code records_skipped} true at the entity level, but the
   * REST DTO stayed silent about it. A run seeded with a nonzero skip count
   * must serialize {@code recordsSkipped} in the JSON.
   */
  @Test
  void get_run_exposes_recordsSkipped() throws Exception {
    UUID runId = seedRunWithRecordsSkipped(3);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(200)
        .body("recordsSkipped", equalTo(3));
  }

  /**
   * The five cell hooks (parse-date, parse-number, parse-email, parse-phone, lookup) null a
   * value they cannot parse and let the row through — {@code cell_errors} on the entity is the
   * only trace. A run seeded with a nonzero count must serialize {@code cellErrors} in the JSON,
   * the same way #250/#251 made {@code recordsSkipped} true at the DTO level.
   */
  @Test
  void get_run_exposes_cellErrors() throws Exception {
    UUID runId = seedRunWithCellErrors(5);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(200)
        .body("cellErrors", equalTo(5));
  }

  /**
   * The run ledger exists for HUMAN diagnosis, and until now it spoke only in
   * connector UUIDs — the first real Windows test needed a second person to map
   * a Failed run back to its connector. Both doors must name the connector.
   */
  @Test
  void runs_expose_the_connector_name() throws Exception {
    String name = "named-conn-" + System.nanoTime();
    UUID runId = seedNamedRun(name);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(200)
        .body("connectorName", equalTo(name));

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("limit", 50)
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("findAll { it.id == '" + runId + "' }.connectorName", hasItem(equalTo(name)));
  }

  /**
   * The ledger is read top-down for "what just happened", so the list must be newest first —
   * {@code findAll()} alone is unordered. Two runs an hour apart on one connector; the newer must
   * appear before the older in the response.
   */
  @Test
  void get_other_tenant_run_is_404() throws Exception {
    // Same guard as the DLQ ledger: EntityManager.find() bypasses the tenant filter, so the
    // by-id read carries an explicit tenantId predicate — RLS is the floor, this is the belt.
    UUID runId = seedRunForTenant(2L);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(404);
  }

  @Test
  void list_is_newest_first() throws Exception {
    UUID[] ids = seedTwoRunsOldThenNew();
    String older = ids[0].toString();
    String newer = ids[1].toString();

    List<String> order = given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("limit", 200)
      .when().get("/api/platform/runs")
      .then().statusCode(200)
      .extract().path("id");

    assertThat(order.indexOf(newer))
      .as("the newer run must appear before the older one")
      .isGreaterThanOrEqualTo(0)
      .isLessThan(order.indexOf(older));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** Seeds one connector with two Completed runs an hour apart; returns {older, newer} ids. */
  private UUID[] seedTwoRunsOldThenNew() throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("run-order-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorRun older = new ConnectorRun();
        older.setTenantId(1L);
        older.setConnectorId(c.getId());
        older.setStatus("Completed");
        older.setSyncMode("full");
        older.setTriggeredBy("Manual");
        older.setStartedAt(Instant.now().minusSeconds(3600));
        runs.persist(older);

        ConnectorRun newer = new ConnectorRun();
        newer.setTenantId(1L);
        newer.setConnectorId(c.getId());
        newer.setStatus("Completed");
        newer.setSyncMode("full");
        newer.setTriggeredBy("Manual");
        newer.setStartedAt(Instant.now());
        runs.persist(newer);
        runs.flush();

        utx.commit();
        return new UUID[]{older.getId(), newer.getId()};
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }

  /** Seeds a connector with the given name and one Completed run; returns the run id. */
  private UUID seedRunForTenant(long tenantId) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        Connector c = new Connector();
        c.setTenantId(tenantId);
        c.setName("run-admin-tenant-" + tenantId + "-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();
        ConnectorRun run = new ConnectorRun();
        run.setTenantId(tenantId);
        run.setConnectorId(c.getId());
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        runs.flush();
        utx.commit();
        return run.getId();
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }

  private UUID seedNamedRun(String name) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName(name);
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(c.getId());
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        runs.flush();

        utx.commit();
        return run.getId();
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }

  /** Seeds a connector with a Completed run carrying the given records_skipped; returns the run id. */
  private UUID seedRunWithRecordsSkipped(long skipped) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("run-admin-skipped-test-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(c.getId());
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        run.setRecordsSkipped(skipped);
        runs.persist(run);
        runs.flush();

        utx.commit();
        return run.getId();
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }

  /** Seeds a connector with a Completed run carrying the given cell_errors; returns the run id. */
  private UUID seedRunWithCellErrors(long cellErrors) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("run-admin-cell-errors-test-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(c.getId());
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        run.setCellErrors(cellErrors);
        runs.persist(run);
        runs.flush();

        utx.commit();
        return run.getId();
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }

  /**
   * Seeds a connector → run under tenant 1, then captures {@code lossCount}
   * real-loss dead-letters (ValidationFailed) and one benign Duplicate that
   * must NOT appear in the count.
   *
   * @return the run UUID
   */
  private UUID seedRunWithDeadLetters(int lossCount) throws Exception {
    var empty = JsonNodeFactory.instance.objectNode();

    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("run-admin-test-" + System.nanoTime());
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
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        runs.flush();

        utx.commit();

        UUID runId = run.getId();
        UUID connectorId = c.getId();

        // Capture dead-letters outside the schema-persist transaction
        for (int i = 0; i < lossCount; i++) {
          capture.capture(connectorId, runId, empty, new SkipReason.ValidationFailed(List.of("field " + i + " invalid")));
        }
        // One benign skip — must not inflate the count
        capture.capture(connectorId, runId, empty, new SkipReason.Duplicate("BK-dup"));

        return runId;
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });
  }
}
