package io.lumnik.hub.connector.rls;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE chantier acceptance test, on the enforced-RLS harness. Red-first: pre-fix,
 * POST {id}/run returns 404 (finding lm-connector-run-rls-404) where this asserts 202.
 * Post-fix it proves: the run works as the request tenant, rows land, cross-tenant access
 * stays closed, and the system sentinel is not a tenant backdoor.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectorRunRlsIT {

  @Inject AgroalDataSource appDs;   // default DS = lumnik_app (RLS enforced)

  private static String connectorId;

  /** Count rows of {@code sql} inside a tenant GUC transaction. Returns 0 if the table doesn't exist. */
  private long countAs(long tenant, String sql) {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
      try (ResultSet rs = st.executeQuery(sql)) {
        rs.next();
        long n = rs.getLong(1);
        st.execute("COMMIT");
        return n;
      } catch (org.postgresql.util.PSQLException pse) {
        // "relation does not exist" (SQLSTATE 42P01) — table not yet created; treat as zero.
        if ("42P01".equals(pse.getSQLState())) {
          st.execute("ROLLBACK");
          return 0L;
        }
        throw new RuntimeException(pse);
      }
    } catch (RuntimeException re) { throw re; }
      catch (Exception e) { throw new RuntimeException(e); }
  }

  @Test @Order(1)
  void tenant1_can_run_its_own_connector_and_rows_land() throws Exception {
    Path csv = Files.createTempFile("rls_csv_", ".csv");
    csv.toFile().deleteOnExit();
    Files.writeString(csv, "row_id,email\n1,a@x\n2,b@x\n", StandardCharsets.UTF_8);

    String manifest = """
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: rls-csv
          connector_type: csv-file
          tenant: 1
          tags: [rls]
        spec:
          transport:
            kind: local
            path: %s
          parser:
            has_header: true
          after_process: none
          target:
            table_prefix: t_
            tags: [rls]
        """.formatted(csv.toAbsolutePath());

    // Apply as tenant 1 — the same path `lm apply` uses; creates connector + endpoint.
    // ApplyResult record: { name, connectorId (UUID), endpointsApplied }
    // RestAssured doesn't know application/yaml natively; tell it to treat it as TEXT.
    RestAssuredConfig yamlCfg = RestAssuredConfig.config().encoderConfig(
        EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));
    connectorId = given()
        .config(yamlCfg)
        .header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType("application/yaml")
        .body(manifest)
        .post("/api/platform/connectors/apply")
        .then().statusCode(200)
        .extract().path("connectorId");   // ApplyResult.connectorId (UUID serialized as string)

    // THE assertion that is RED pre-fix: the run must be accepted (202), not 404.
    // Pre-fix this returned 404: engine.exists() read in system mode while the interceptor
    // skipped the GUC (finding lm-connector-run-rls-404).
    given()
        .header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .post("/api/platform/connectors/" + connectorId + "/run")
        .then().statusCode(202);

    // The rows land, visible to tenant 1 (poll up to 60s).
    long n = 0;
    for (int i = 0; i < 30; i++) {
      n = countAs(1, "SELECT count(*) FROM connector.t_rls_csv");
      if (n >= 2) break;
      Thread.sleep(2000);
    }
    assertThat(n).as("ingested rows visible to tenant 1").isEqualTo(2L);
  }

  @Test @Order(2)
  void tenant2_cannot_see_or_run_tenant1s_connector() {
    given().header("X-Tenant-ID", "2").header("X-User-ID", "2")
        .get("/api/platform/connectors/" + connectorId)
        .then().statusCode(404);
    given().header("X-Tenant-ID", "2").header("X-User-ID", "2")
        .post("/api/platform/connectors/" + connectorId + "/run")
        .then().statusCode(404);
    assertThat(countAs(2, "SELECT count(*) FROM connector.t_rls_csv"))
        .as("tenant 2 must not read tenant 1's ingested rows").isEqualTo(0L);
  }

  @Test @Order(3)
  void sentinel_is_not_a_tenant_backdoor() throws Exception {
    // A plain app connection with NO tenant and NO system mode sees nothing.
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*) FROM connector.connector")) {
      rs.next();
      assertThat(rs.getLong(1)).as("no tenant + no system = zero rows").isEqualTo(0L);
    }
  }

  @Test @Order(4)
  void claimed_tenant_zero_is_not_a_sentinel_backdoor() {
    // A request CLAIMING tenant 0 must not be elevated to cross-tenant system access.
    given().header("X-Tenant-ID", "0").header("X-User-ID", "0")
        .get("/api/platform/connectors/" + connectorId)
        .then().statusCode(404);
    given().header("X-Tenant-ID", "0").header("X-User-ID", "0")
        .post("/api/platform/connectors/" + connectorId + "/run")
        .then().statusCode(404);
  }
}
