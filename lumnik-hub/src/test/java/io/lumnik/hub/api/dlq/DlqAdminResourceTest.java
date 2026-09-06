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
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
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

  // ── helpers ──────────────────────────────────────────────────────────────────

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
