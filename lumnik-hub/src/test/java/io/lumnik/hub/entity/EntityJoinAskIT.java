package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.testkit.auth.TestIdentity;
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
 * #846: a joined column is not only advertised to Ask, it is ASKABLE — the generated SQL over it
 * passes the guards and executes on the readonly role through the joining view. Bare @QuarkusTest and
 * tables without tenant_id, like AskToSqlAgentIT: what is under test is the Ask path, not RLS.
 */
@QuarkusTest
class EntityJoinAskIT {

  @Inject AskToSqlAgent agent;
  @Inject SchemaCardStore store;
  @Inject EntitySchemaBuilder schemas;
  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @InjectMock @Analytic ChatPort chatPort;

  static final String MANIFEST = """
      kind: Entity
      name: AskJoinProduct
      match: [product_id]
      scopes: [askjoin]
      sources:
        - table: t_askjoin_products
          map: { pid: product_id, cid: category_id, name: product_name }
      joins:
        - table: t_askjoin_categories
          from: category_id
          to: cid
          map: { name: category_name }
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_askjoinproduct\"");
      st.execute("DROP TABLE IF EXISTS connector.t_askjoin_products");
      st.execute("DROP TABLE IF EXISTS connector.t_askjoin_categories");
      st.execute("CREATE TABLE connector.t_askjoin_products (pid text, cid text, name text)");
      st.execute("CREATE TABLE connector.t_askjoin_categories (cid text, name text)");
      st.execute("INSERT INTO connector.t_askjoin_categories VALUES ('C1', 'Pumps')");
      st.execute("INSERT INTO connector.t_askjoin_products VALUES ('P1', 'C1', 'Pump A'), ('P2', 'C1', 'Pump B')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
    store.save(7L, "askjoin", new SchemaCard("askjoin", schemas.resolve("askjoin")));
  }

  @Test
  void a_question_over_a_joined_column_is_generated_guarded_executed_and_answered() {
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT product_name, category_name FROM connector.v_askjoinproduct "
            + "WHERE category_name = 'Pumps' ORDER BY product_name")
        .thenReturn("Two products are in the Pumps category.");

    AskResult r = agent.ask(7L, "askjoin", "which products are pumps");

    assertThat(r.sql()).contains("category_name");
    assertThat(r.rowCount()).isEqualTo(2);
    assertThat(r.rows().get(0)).containsEntry("category_name", "Pumps");
    assertThat(r.rows().get(0)).containsEntry("product_name", "Pump A");
  }
}
