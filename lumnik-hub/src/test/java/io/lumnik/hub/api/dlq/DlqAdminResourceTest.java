package io.lumnik.hub.api.dlq;

import io.lumnik.testkit.auth.TestIdentity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.DeadLetterCapture;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class DlqAdminResourceTest {

  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorDeadLetterRepository dlq;
  @Inject DeadLetterCapture capture;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource admin;

  @Test
  void list_returns_empty_or_existing_entries() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq")
      .then().statusCode(200);
  }

  @Test
  void list_run_filter_returns_seeded_entry_with_skipDetail() throws Exception {
    UUID runId = seedRunWithDeadLetter();

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("run", runId.toString())
      .when().get("/api/platform/dlq")
      .then().statusCode(200)
        .body("size()", greaterThanOrEqualTo(1))
        .body("runId", hasItem(equalTo(runId.toString())))
        .body("skipDetail", hasItem(notNullValue()));
  }

  @Test
  void list_and_get_carry_the_connector_name_like_the_run_ledger() throws Exception {
    // Like /runs, the queue names the connector for humans next to the id it keeps for scripts.
    UUID runId = seedRunWithDeadLetter();   // seeds a connector named dlq-admin-test-<nanos>

    String dlqId = given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("run", runId.toString())
      .when().get("/api/platform/dlq")
      .then().statusCode(200)
        .body("connectorName", hasItem(startsWith("dlq-admin-test-")))
      .extract().path("[0].id");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + dlqId)
      .then().statusCode(200)
        .body("connectorName", startsWith("dlq-admin-test-"));
  }

  @Test
  void get_unknown_id_is_404() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + UUID.randomUUID())
      .then().statusCode(404);
  }

  @Test
  void get_known_id_returns_200_with_skipDetail() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + dlqId)
      .then().statusCode(200)
        .body("skipReason", notNullValue())
        .body("skipDetail", notNullValue());
  }

  // Fix A — GDPR soft-delete: detail route must treat soft-deleted row as absent (404)
  @Test
  void get_soft_deleted_id_returns_404() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();

    // Soft-delete the row inside a system transaction
    TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        ConnectorDeadLetter letter = dlq.findById(dlqId);
        letter.softDelete();
        dlq.persist(letter);
        utx.commit();
        return null;
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + dlqId)
      .then().statusCode(404);
  }

  @Test
  void list_excludes_soft_deleted() throws Exception {
    // The no-filter list must not leak a soft-deleted (GDPR-erased) row's payload — consistent with
    // GET /{id} and the by-run/by-connector branches.
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        ConnectorDeadLetter letter = dlq.findById(dlqId);
        letter.softDelete();
        dlq.persist(letter);
        utx.commit();
        return null;
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq")
      .then().statusCode(200)
        .body("findAll { it.id == '" + dlqId + "' }.size()", equalTo(0));
  }

  // Fix C — cross-tenant: detail route must not leak another tenant's payload via RLS
  @Test
  void get_other_tenant_id_returns_404() throws Exception {
    UUID dlqId = seedRunWithDeadLetterForTenant(2L);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + dlqId)
      .then().statusCode(404);
  }

  @Test
  void list_malformed_run_uuid_returns_400() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("run", "not-a-uuid")
      .when().get("/api/platform/dlq")
      .then().statusCode(400);
  }

  /** The same refusal as the list's: a malformed id is a 400 with its reason, not a "not found" (#1294). */
  @Test
  void replayed_malformed_new_run_uuid_returns_400() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .queryParam("newRunId", "not-a-uuid")
      .when().post("/api/platform/dlq/" + dlqId + "/replayed")
      .then().statusCode(400)
        .body("error", equalTo("invalid UUID: not-a-uuid"));
  }

  // #1375: a resolution is a claim the hub keeps — it takes a run that can back it, and only once.

  @Test
  void replayed_on_a_later_completed_run_of_its_connector_closes_it() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    UUID proof = seedRun(connectorOf(dlqId), "Completed");

    replayed(dlqId, proof).then().statusCode(204);
    given().headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/dlq/" + dlqId)
      .then().statusCode(200)
        .body("resolution", equalTo("REPLAYED"))
        .body("resolvedRunId", equalTo(proof.toString()))
        .body("resolvedAt", notNullValue());
    // The conditional write bypasses @PreUpdate: it must stamp updatedAt itself.
    try (var c = admin.getConnection(); var st = c.createStatement();
         var rs = st.executeQuery("SELECT updated_at = resolved_at FROM connector.connector_dead_letter WHERE id = '"
             + dlqId + "'")) {
      assertThat(rs.next() && rs.getBoolean(1)).as("updated_at stamped with the resolution").isTrue();
    }
  }

  /** #1377: a replay with no run is a claim nothing backs — that is what discard is for. */
  @Test
  void replayed_without_a_run_is_refused_and_leaves_the_letter_open() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();

    given().headers(TestIdentity.tenantAndUser(1))
      .when().post("/api/platform/dlq/" + dlqId + "/replayed")
      .then().statusCode(400)
        .body("error", equalTo("a replay names the run that carried the row (newRunId);"
            + " an entry no run carried is closed with discard"));
    discard(dlqId).then().statusCode(204);
  }

  /** #1377 review: a blank newRunId is no run either — the same refusal, not "invalid UUID: ". */
  @Test
  void replayed_with_a_blank_run_is_refused_like_no_run() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();

    given().headers(TestIdentity.tenantAndUser(1))
      .queryParam("newRunId", " ")
      .when().post("/api/platform/dlq/" + dlqId + "/replayed")
      .then().statusCode(400)
        .body("error", startsWith("a replay names the run that carried the row"));
  }

  /** #1377, V78: the table itself refuses a REPLAYED with no run, whoever writes it. */
  @Test
  void the_table_refuses_a_replay_without_a_run() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    try (var c = admin.getConnection(); var st = c.createStatement()) {
      assertThatThrownBy(() -> st.executeUpdate("UPDATE connector.connector_dead_letter"
          + " SET resolution = 'REPLAYED', resolved_run_id = NULL WHERE id = '" + dlqId + "'"))
        .hasMessageContaining("connector_dead_letter_replayed_names_a_run");
    }
  }

  @Test
  void replayed_on_an_unknown_run_is_404_not_500() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    UUID unknown = UUID.randomUUID();

    replayed(dlqId, unknown).then().statusCode(404)
        .body("error", equalTo("run " + unknown + " not found"));
  }

  @Test
  void replayed_on_a_failed_run_is_refused() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    UUID failed = seedRun(connectorOf(dlqId), "Failed");

    replayed(dlqId, failed).then().statusCode(400)
        .body("error", containsString("ended Failed"));
  }

  @Test
  void replayed_on_another_connectors_run_is_refused() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    UUID otherConnector = connectorOf(seedRunWithDeadLetterReturnDlqId());
    UUID foreign = seedRun(otherConnector, "Completed");

    replayed(dlqId, foreign).then().statusCode(400)
        .body("error", containsString("another connector"));
  }

  @Test
  void replayed_on_the_run_that_captured_it_is_refused() throws Exception {
    // The capturing run started before the letter existed: it cannot have re-sent the row.
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    UUID capturing = TenantContext.callAsSystem(() -> dlq.findById(dlqId).getRunId());

    replayed(dlqId, capturing).then().statusCode(400)
        .body("error", containsString("started before"));
  }

  @Test
  void a_closed_letter_refuses_a_second_resolution() throws Exception {
    UUID dlqId = seedRunWithDeadLetterReturnDlqId();
    discard(dlqId).then().statusCode(204);

    discard(dlqId).then().statusCode(409).body("error", equalTo("DLQ entry " + dlqId + " is already DISCARDED"));
    replayed(dlqId, seedRun(connectorOf(dlqId), "Completed")).then().statusCode(409);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private Response replayed(UUID dlqId, UUID newRunId) {
    return given().headers(TestIdentity.tenantAndUser(1))
      .queryParam("newRunId", newRunId.toString())
      .when().post("/api/platform/dlq/" + dlqId + "/replayed");
  }

  private Response discard(UUID dlqId) {
    return given().headers(TestIdentity.tenantAndUser(1))
      .when().post("/api/platform/dlq/" + dlqId + "/discard");
  }

  private UUID connectorOf(UUID dlqId) throws Exception {
    return TenantContext.callAsSystem(() -> dlq.findById(dlqId).getConnectorId());
  }

  /** A run of {@code connectorId} (tenant 1) started now — after any letter already captured. */
  private UUID seedRun(UUID connectorId, String status) throws Exception {
    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(connectorId);
        run.setStatus(status);
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        utx.commit();
        return run.getId();
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });
  }

  private UUID seedRunWithDeadLetter() throws Exception {
    var empty = JsonNodeFactory.instance.objectNode();

    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("dlq-admin-test-" + System.nanoTime());
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

        capture.capture(connectorId, runId, empty, new SkipReason.ValidationFailed(List.of("field X invalid")));

        return runId;
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });
  }

  private UUID seedRunWithDeadLetterReturnDlqId() throws Exception {
    return seedRunWithDeadLetterForTenant(1L);
  }

  private UUID seedRunWithDeadLetterForTenant(long tenantId) throws Exception {
    var empty = JsonNodeFactory.instance.objectNode();

    return TenantContext.callAsSystem(() -> {
      try {
        utx.begin();

        Connector c = new Connector();
        c.setTenantId(tenantId);
        c.setName("dlq-detail-test-" + System.nanoTime());
        c.setTypeId("csv-file");
        c.setConfig(Map.of("kind", "csv-file"));
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setTenantId(tenantId);
        ep.setConnectorId(c.getId());
        ep.setName("default");
        ep.setMethod("GET");
        ep.setPath("/test");
        ep.setEnabled(true);
        ep.setConfig(Map.of("name", "test_table"));
        endpoints.persist(ep);
        endpoints.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(tenantId);
        run.setConnectorId(c.getId());
        run.setStatus("Completed");
        run.setSyncMode("full");
        run.setTriggeredBy("Manual");
        runs.persist(run);
        runs.flush();

        utx.commit();

        return capture.capture(c.getId(), run.getId(), empty,
            new SkipReason.ValidationFailed(List.of("detail test")));
      } catch (Exception e) {
        try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
        throw new RuntimeException(e);
      }
    });
  }
}
