package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1209: a decision belongs to the métier of the Process or Workflow that raised it. A user bound
 * to one métier sees, and acknowledges, only that métier's decisions — another's dormant documents
 * and process messages answer as if they did not exist. An unbound user keeps the whole pile.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class InboxScopeIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private long ventes, achats, orphan;

  /** One decision of a ventes Process, one of an achats Workflow, one whose Process is gone. */
  @BeforeEach
  void seed() throws Exception {
    String n = String.valueOf(System.nanoTime());
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) "
          + "VALUES (1, 'p-ventes-" + n + "', '-', 'ventes', 'connector.t_x', 'decision.order-on-hold')");
      st.execute("INSERT INTO core.workflow_manifest (tenant_id, name, yaml_text, scope, on_table, on_column) "
          + "VALUES (1, 'w-achats-" + n + "', '-', 'achats', 'connector.t_po_" + n + "', 'status')");
      ventes = decision(st, "decision.order-on-hold", "process:p-ventes-" + n);
      achats = decision(st, "decision.dormant.w-achats-" + n, "workflow:w-achats-" + n);
      orphan = decision(st, "decision.order-on-hold", "process:gone-" + n);
    }
  }

  private static long decision(Statement st, String type, String source) throws Exception {
    try (ResultSet rs = st.executeQuery("INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) VALUES "
        + "(1, '" + type + "', '" + source + "', 'connector.t_x', 'K-1', '{\"message\":\"m\"}'::jsonb, NOW(), NOW()) "
        + "RETURNING id")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static RequestSpecification as(String... roles) {
    return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null));
  }

  private static List<Long> inbox(RequestSpecification caller) {
    return caller.get("/api/inbox?limit=200&all=true").then().statusCode(200)
        .extract().jsonPath().getList("id", Long.class);
  }

  @Test
  void a_bound_user_sees_only_their_metiers_decisions() {
    assertThat(inbox(as("lm_user", "scope:ventes"))).contains(ventes).doesNotContain(achats, orphan);
    assertThat(inbox(as("lm_user", "scope:achats"))).contains(achats).doesNotContain(ventes, orphan);
  }

  @Test
  void an_unbound_user_keeps_the_whole_pile() {
    assertThat(inbox(as("lm_user"))).contains(ventes, achats, orphan);
  }

  @Test
  void another_metiers_decision_cannot_be_acknowledged() {
    as("lm_user", "scope:ventes").post("/api/inbox/" + achats + "/ack").then().statusCode(404);
    as("lm_user", "scope:ventes").post("/api/inbox/" + ventes + "/ack").then().statusCode(200);
  }
}
