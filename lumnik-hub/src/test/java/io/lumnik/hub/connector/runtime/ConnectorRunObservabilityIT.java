package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the connector "silent no-op" bug at the HTTP contract level
 * ([[project-connector-run-async-fix]]).
 *
 * <p>{@code POST /api/platform/connectors/{id}/run} ({@code ConnectorAdminResource.runNow})
 * fires {@code engine.submitAsync(...)} and returns HTTP 202 with a FAKE runId
 * {@code "scheduled:" + future.hashCode()}. It never awaits the Future and never returns a
 * real, dereferenceable run identity. A caller therefore cannot observe whether the run
 * succeeded or failed — the run outcome is unobservable (the silent no-op).
 *
 * <p>This test asserts the contract that SHOULD hold: the returned {@code runId} is a real
 * run identity — a UUID that, after the async run finishes, exists as a terminal row in
 * {@code connector.connector_run} for the tenant, with {@code records_in = 600} (the
 * hello-world connector ingests 600 records).
 *
 * <p><b>RED today</b> because the returned id is {@code "scheduled:<hashCode>"}: it is not a
 * UUID and never matches any run row.
 */
@QuarkusTest
class ConnectorRunObservabilityIT {

  private static final int EXPECTED_RECORDS = 600;

  @Inject ConnectorRepository connectors;
  @Inject UserTransaction utx;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Test
  @Timeout(90)
  void runNow_returns_an_observable_run_id() throws Exception {
    UUID connectorId = TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        var c = new Connector();
        c.setTenantId(1L);
        c.setName("obs-hello-" + System.nanoTime());
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", EXPECTED_RECORDS));
        connectors.persist(c);
        connectors.flush();
        utx.commit();
        return c.getId();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    String runId =
        given()
            .header("X-Tenant-ID", "1")
            .header("X-User-ID", "1")
            .contentType(ContentType.JSON)
            .when()
            .post("/api/platform/connectors/{id}/run", connectorId.toString())
            .then()
            .statusCode(202)
            .extract()
            .path("runId");

    assertThat(runId).as("runNow must return a runId").isNotNull();

    // CONTRACT (fails today): the returned id is a real, dereferenceable run identity.
    UUID runUuid =
        tryParseUuid(runId)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "runNow returned an unresolvable fake runId '"
                            + runId
                            + "' (expected a UUID identifying a connector_run row). "
                            + "The run outcome is unobservable — the silent no-op."));

    // Poll connector.connector_run for up to ~30s for the terminal row with this exact id.
    long deadline = System.currentTimeMillis() + 30_000;
    String status = null;
    long recordsIn = -1;
    while (System.currentTimeMillis() < deadline) {
      var row = lookupRun(runUuid);
      if (row != null && isTerminal(row.status)) {
        status = row.status;
        recordsIn = row.recordsIn;
        break;
      }
      Thread.sleep(500);
    }

    assertThat(status)
        .as(
            "the returned runId '%s' must resolve to a terminal connector_run row for tenant 1",
            runUuid)
        .isNotNull();
    assertThat(status)
        .as("the run must terminate successfully (RunStatus.Completed)")
        .isEqualTo("Completed");
    assertThat(recordsIn)
        .as("the hello-world run must have ingested %d records", EXPECTED_RECORDS)
        .isEqualTo(EXPECTED_RECORDS);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private record RunRow(String status, long recordsIn) {}

  private RunRow lookupRun(UUID runId) {
    String sql =
        "SELECT status, records_in FROM connector.connector_run "
            + "WHERE id = '" + runId + "' AND tenant_id = 1";
    try (Connection c = adminDs.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      if (rs.next()) {
        return new RunRow(rs.getString("status"), rs.getLong("records_in"));
      }
      return null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean isTerminal(String status) {
    return "Completed".equals(status)
        || "Failed".equals(status)
        || "Partial".equals(status)
        || "Cancelled".equals(status);
  }

  private static Optional<UUID> tryParseUuid(String s) {
    try {
      return Optional.of(UUID.fromString(s));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
