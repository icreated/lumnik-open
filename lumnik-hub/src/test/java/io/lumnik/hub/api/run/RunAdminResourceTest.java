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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

  /**
   * A connector fans out over its endpoints and writes ONE run row per endpoint — so a two-table
   * jdbc connector logs two rows per run. Until now those rows were indistinguishable: same
   * connector name, same trigger, same instant, only the counters differed, and the docs told the
   * reader to tell them apart by counting rows. The engine's own log line has named the endpoint
   * since the run-lifecycle log; the ledger both doors read did not.
   */
  @Test
  void two_runs_of_one_connector_are_told_apart_by_their_endpoint() throws Exception {
    Map<String, Seeded> runByEndpoint = seedRunPerEndpoint(List.of("warehouses", "price_list"));
    String warehouses = runByEndpoint.get("warehouses").runId().toString();
    String priceList = runByEndpoint.get("price_list").runId().toString();

    var body = given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("limit", 200)
      .when().get("/api/platform/runs")
      .then().statusCode(200).extract().body();

    assertThat(body.<List<String>>path("findAll { it.id == '" + warehouses + "' }.endpointName"))
      .as("the run that read `warehouses` must say so")
      .containsExactly("warehouses");
    assertThat(body.<List<String>>path("findAll { it.id == '" + priceList + "' }.endpointName"))
      .as("its sibling row, same connector and same instant, must name the OTHER table")
      .containsExactly("price_list");
  }

  /** The id rides along with the name: a script filters on it, a human reads the name. */
  @Test
  void get_run_exposes_the_endpoint_id_and_name() throws Exception {
    Seeded orders = seedRunPerEndpoint(List.of("orders")).get("orders");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + orders.runId())
      .then().statusCode(200)
        .body("endpointName", equalTo("orders"))
        .body("endpointId", equalTo(orders.endpointId().toString()));
  }

  /**
   * A run row predating endpoints, or one whose endpoint was hard-deleted ({@code ON DELETE SET
   * NULL}, V13), carries no endpoint_id. The ledger must stay silent about it rather than invent
   * a name — the client renders an empty cell, as it already does for a run with no end time.
   */
  @Test
  void a_run_with_no_endpoint_names_none() throws Exception {
    UUID runId = seedNamedRun("run-no-endpoint-" + System.nanoTime());

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/runs/" + runId)
      .then().statusCode(200)
        .body("endpointName", nullValue())
        .body("endpointId", nullValue());
  }

  /**
   * The ledger is global and newest-first, so a busy hub scrolls a quiet connector's runs out of
   * any page a reader asks for. Narrowing client-side then answers about the PAGE, not about the
   * connector — a bare table read as "this connector never ran". The filter belongs to the query:
   * a limit of 1 must return that connector's newest run, not the hub's.
   */
  @Test
  void narrowing_to_a_connector_finds_a_run_the_global_page_scrolled_past() throws Exception {
    var quiet = seedConnectorWithRun(1L, "quiet-" + System.nanoTime(), Instant.now().minusSeconds(7200), false);
    var noisy = seedConnectorWithRun(1L, "noisy-" + System.nanoTime(), Instant.now(), false);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("connector", quiet.connectorId().toString())
      .queryParam("limit", 1)
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("id", hasItem(quiet.runId().toString()))
        .body("findAll { it.id == '" + noisy.runId() + "' }", empty());
  }

  /**
   * Narrowing must RESTRICT the ledger, never redefine it: {@code findRecentActiveByConnector} — the
   * by-connector view the connector pages use — hides soft-deleted runs, which the ledger itself
   * shows. Wiring the filter to it would make a run visible without the filter vanish with it,
   * the very silence this filter exists to end.
   */
  @Test
  void narrowing_to_a_connector_keeps_the_ledgers_own_set() throws Exception {
    var deleted = seedConnectorWithRun(1L, "softdel-" + System.nanoTime(), Instant.now(), true);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("connector", deleted.connectorId().toString())
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("id", hasItem(deleted.runId().toString()));
  }

  /**
   * The TUI drills from an endpoint into "that endpoint's own runs" — a promise its own reference
   * page makes. Until the ledger could be asked by endpoint, that drill-down had nothing to ask
   * with and answered an empty table. A connector fans out over its endpoints, so the connector
   * filter cannot stand in: both of these rows belong to the same connector.
   */
  @Test
  void narrowing_to_an_endpoint_returns_only_that_endpoints_runs() throws Exception {
    var seeded = seedRunPerEndpoint(List.of("warehouses", "price_list"));
    Seeded warehouses = seeded.get("warehouses");
    Seeded priceList = seeded.get("price_list");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("endpoint", warehouses.endpointId().toString())
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("id", hasItem(warehouses.runId().toString()))
        .body("findAll { it.id == '" + priceList.runId() + "' }", empty());
  }

  /** Both filters narrow; naming an endpoint together with its connector simply agrees with itself. */
  @Test
  void narrowing_by_connector_and_endpoint_together_narrows_twice() throws Exception {
    var seeded = seedRunPerEndpoint(List.of("warehouses", "price_list"));
    Seeded warehouses = seeded.get("warehouses");
    var other = seedConnectorWithRun(1L, "other-" + System.nanoTime(), Instant.now(), false);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("connector", other.connectorId().toString())
      .queryParam("endpoint", warehouses.endpointId().toString())
      .when().get("/api/platform/runs")
      .then().statusCode(200)
        .body("$", empty());
  }

  /** The endpoint filter is refused the same way as its connector twin — never ignored. */
  @Test
  void narrowing_to_an_endpoint_that_is_not_a_uuid_is_refused() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("endpoint", "warehouses")
      .when().get("/api/platform/runs")
      .then().statusCode(400)
        .body("error", equalTo("invalid UUID: warehouses"));
  }

  /** Same refusal as the DLQ door's ?connector=: a JSON 400, not a 500 and not a silent full page. */
  @Test
  void narrowing_to_something_that_is_not_a_uuid_is_refused() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("connector", "demo-erp")
      .when().get("/api/platform/runs")
      .then().statusCode(400)
        .body("error", equalTo("invalid UUID: demo-erp"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** What one seeded endpoint left behind: its run, and the endpoint that run points at. */
  private record Seeded(UUID runId, UUID endpointId) {}

  /**
   * One connector, one endpoint per given name, one Completed run on each — the shape a fan-out
   * over a multi-table source leaves in the ledger. Returns endpoint name → run id.
   */
  private Map<String, Seeded> seedRunPerEndpoint(List<String> endpointNames) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("run-endpoint-" + System.nanoTime());
        c.setTypeId("jdbc-generic");
        c.setConfig(Map.of("kind", "jdbc-generic"));
        connectors.persist(c);
        connectors.flush();

        Map<String, Seeded> byEndpoint = new LinkedHashMap<>();
        for (String endpointName : endpointNames) {
          ConnectorEndpoint ep = new ConnectorEndpoint();
          ep.setTenantId(1L);
          ep.setConnectorId(c.getId());
          ep.setName(endpointName);
          ep.setMethod("GET");
          ep.setPath("/" + endpointName);
          ep.setEnabled(true);
          ep.setConfig(Map.of("name", endpointName));
          endpoints.persist(ep);
          endpoints.flush();

          ConnectorRun run = new ConnectorRun();
          run.setTenantId(1L);
          run.setConnectorId(c.getId());
          run.setEndpointId(ep.getId());
          run.setStatus("Completed");
          run.setSyncMode("full");
          run.setTriggeredBy("Manual");
          runs.persist(run);
          runs.flush();

          byEndpoint.put(endpointName, new Seeded(run.getId(), ep.getId()));
        }

        utx.commit();
        return byEndpoint;
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }


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

  /** A connector owned by the given tenant, with one Completed run; returns the run id. */
  private UUID seedRunForTenant(long tenantId) throws Exception {
    return seedConnectorWithRun(tenantId, "run-admin-tenant-" + tenantId + "-" + System.nanoTime(),
        Instant.now(), false).runId();
  }

  /**
   * A connector with the given name and one Completed run; returns the run id. The general seeder
   * below does the work — this only names what the callers care about.
   */
  private UUID seedNamedRun(String name) throws Exception {
    return seedConnectorWithRun(1L, name, Instant.now(), false).runId();
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

  /** What one seeded connector left behind: the connector, and its single run. */
  private record SeededRun(UUID connectorId, UUID runId) {}

  /**
   * One connector with one Completed run started at the given instant, optionally soft-deleted —
   * the general shape the named seeders above delegate to, so the frame (system context, the
   * transaction, ConnectorRun's required fields) is written once.
   */
  private SeededRun seedConnectorWithRun(long tenantId, String name, Instant startedAt, boolean softDeleted)
      throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(tenantId);
        c.setName(name);
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
        run.setStartedAt(startedAt);
        if (softDeleted) run.softDelete();
        runs.persist(run);
        runs.flush();

        utx.commit();
        return new SeededRun(c.getId(), run.getId());
      } catch (Exception e) {
        utx.rollback();
        throw e;
      }
    });
  }
}
