package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The honesty guard, end-to-end: a manifest declares segment's value-domain; the model emits a filter
 * on a value OUTSIDE that domain ('PLATINUM'); the system REFUSES before executing — it never runs the
 * query and never phrases a deceptive "there are none." The in-domain control runs normally.
 */
@QuarkusTest
class EntityRagDomainGuardIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject AskToSqlAgent agent;
  @InjectMock @Analytic ChatPort chatPort;

  static final String MANIFEST = """
      kind: Entity
      name: DgCustomer
      description: "A customer with a commercial segment."
      match: [customer_code]
      scopes: [dg]
      columns:
        segment:
          description: "Commercial segment."
          values: [GOLD, SILVER, BRONZE]
      sources:
        - table: t_dg_src
          map:
            code: customer_code
            seg: segment
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_dgcustomer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_dg_src");
      st.execute("CREATE TABLE connector.t_dg_src (tenant_id bigint NOT NULL, \"code\" text, \"seg\" text)");
      st.execute("DO $$ BEGIN GRANT SELECT ON connector.t_dg_src TO lumnik_app;"
          + " GRANT SELECT ON connector.t_dg_src TO lumnik_readonly;"
          + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
      st.execute("INSERT INTO connector.t_dg_src VALUES (7,'C-1','GOLD')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'dgcustomer'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(7))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void refuses_a_filter_on_a_value_outside_the_declared_domain() {
    // The model invents 'PLATINUM' (not in the declared domain). The SELECT references a NON-EXISTENT
    // column on purpose: if the guard ever regressed and let this run, runReadOnly would throw — so a
    // clean refusal proves the query is truly NEVER executed (not merely "ran and returned 0 rows").
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT no_such_col FROM connector.v_dgcustomer WHERE segment = 'PLATINUM'");

    AskResult r = agent.ask(7L, "dg", "combien de clients Platinum ?");

    assertThat(r.rowCount()).isEqualTo(0);
    assertThat(r.rows()).isEmpty();
    assertThat(r.answer())
        .contains("not a declared value")
        .contains("PLATINUM")
        .contains("GOLD")
        .contains("manifest");
  }

  @Test
  void runs_normally_for_an_in_domain_value() {
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT customer_code FROM connector.v_dgcustomer WHERE segment = 'GOLD'")
        .thenReturn("There is 1 GOLD customer.");

    AskResult r = agent.ask(7L, "dg", "combien de clients GOLD ?");

    assertThat(r.rowCount()).isEqualTo(1);
    assertThat(r.rows().get(0)).containsEntry("customer_code", "C-1");
    assertThat(r.answer()).contains("GOLD");
  }
}
