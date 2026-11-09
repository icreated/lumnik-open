package io.lumnik.hub.api.connector.handler;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

/**
 * #1882: a parse-date added after the table was created. The table keeps the type it was born with, so the apply says
 * it and the gesture; and the files already read are read again, since the mapping that reads them changed.
 */
@QuarkusTest
class CsvMappingChangeApplyIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String RAW = """
      apiVersion: connectors.lumnik.io/v1
      kind: Connector
      metadata:
        name: %s
        connector_type: csv-file
        tenant: 1
        scopes: [crm]
      spec:
        transport: { kind: local, path: /export/deliveries.csv }
        after_process: none
      """;

  private static final String TYPED = RAW + """
        mapping:
          cell:
            - parse-date: { column: delivered, formats: ["dd/MM/yyyy HH:mm"], zone: Europe/Paris }
      """;

  private void apply(String yaml, String name, String warningMessage) {
    var r = given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(yaml.formatted(name))
        .post("/api/platform/connectors/apply").then().statusCode(200);
    if (warningMessage == null) r.body("warnings", org.hamcrest.Matchers.nullValue());
    else r.body("warnings.message", hasItem(containsString(warningMessage)));
  }

  private void seedTableAndCursor(String name, String table, boolean ownRows) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector." + table);
      st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, delivered text)");
      // The rows are another tenant's when they are not the caller's: the table is shared.
      st.execute("INSERT INTO connector." + table + " VALUES (" + (ownRows ? 1 : 2) + ", '05/11/2026 14:02')");
      st.execute("DELETE FROM connector.connector_cursor WHERE endpoint_id IN (SELECT e.id FROM connector.connector_endpoint e"
          + " JOIN connector.connector k ON k.id = e.connector_id WHERE k.name = '" + name + "')");
      st.execute("INSERT INTO connector.connector_cursor (endpoint_id, connector_id, tenant_id, cursor_value, captured_at, version)"
          + " SELECT e.id, e.connector_id, 1, '{\"file\":\"x\"}'::jsonb, now(), 0 FROM connector.connector_endpoint e"
          + " JOIN connector.connector k ON k.id = e.connector_id WHERE k.name = '" + name + "'");
    }
  }

  private int cursors(String name) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*) FROM connector.connector_cursor cu"
             + " JOIN connector.connector_endpoint e ON e.id = cu.endpoint_id"
             + " JOIN connector.connector k ON k.id = e.connector_id WHERE k.name = '" + name + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void aMappingAddedLaterIsSaidAndTheFilesAreReadAgain() throws Exception {
    String name = "csv-drift-" + System.nanoTime();
    String table = "t_" + Identifiers.slug(name, "table");
    apply(RAW, name, null);
    seedTableAndCursor(name, table, true);

    apply(TYPED, name, "delivered");
    assertThat(cursors(name)).as("the mapping changed: nothing is skipped as unchanged").isZero();

    // The same manifest again changes nothing: the cursor of a run that read the files stays.
    seedTableAndCursor(name, table, true);
    apply(TYPED, name, "delivered");
    assertThat(cursors(name)).isEqualTo(1);
  }

  @Test
  void aMappingOverANewTableSaysNothing() throws Exception {
    String name = "csv-drift-new-" + System.nanoTime();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector.t_" + Identifiers.slug(name, "table"));
    }
    apply(TYPED, name, null);
  }

  /** The table is shared between tenants: its types are not told to a caller who has no row in it, whoever else has. */
  @Test
  void aTableWithNoRowOfTheCallersSaysNothing() throws Exception {
    String name = "csv-drift-other-" + System.nanoTime();
    apply(RAW, name, null);
    seedTableAndCursor(name, "t_" + Identifiers.slug(name, "table"), false);
    apply(TYPED, name, null);
  }
}
