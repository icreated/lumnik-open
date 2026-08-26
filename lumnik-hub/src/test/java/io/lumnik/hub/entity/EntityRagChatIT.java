package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EntityRagChatIT {

  @Inject
  @io.quarkus.agroal.DataSource("admin")
  AgroalDataSource adminDs;

  @Inject SchemaCardStore store;
  @Inject AskToSqlAgent agent;
  @InjectMock @Analytic ChatPort chatPort;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  static final String MANIFEST = """
      kind: Entity
      name: ChatCustomer
      match: [customer_code]
      scopes: [clients]
      sources:
        - table: t_chat_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_chat_csv
          map:
            code: customer_code
            ville: city
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_chatcustomer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_chat_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_chat_csv");
      st.execute("CREATE TABLE connector.t_chat_erp (tenant_id bigint NOT NULL, \"cust_id\" text, \"cust_name\" text)");
      st.execute("CREATE TABLE connector.t_chat_csv (tenant_id bigint NOT NULL, \"code\" text, \"ville\" text)");
      for (String t : new String[]{"t_chat_erp", "t_chat_csv"}) {
        // security_invoker view → the querying role (lumnik_app for REST, lumnik_readonly for ask)
        // needs SELECT on the base tables. Connector writers do this; we mirror it for seeded tables.
        st.execute("DO $$ BEGIN GRANT SELECT ON connector." + t + " TO lumnik_app;"
            + " GRANT SELECT ON connector." + t + " TO lumnik_readonly;"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
      }
      st.execute("INSERT INTO connector.t_chat_erp VALUES (7,'C-1','Jean Dupont')");
      st.execute("INSERT INTO connector.t_chat_csv VALUES (7,'C-1','Paris')");
      st.execute("INSERT INTO connector.t_chat_csv VALUES (7,'C-2','Lyon')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'chatcustomer'");
    }
    given().config(YAML_CFG).header("X-Tenant-ID", "7").header("X-User-ID", "1")
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void apply_makes_the_entity_view_discoverable_in_its_scope() {
    Optional<SchemaCard> card = store.load(7L, "clients");
    assertThat(card).isPresent();
    assertThat(card.get().tableNames()).contains("connector.v_chatcustomer");
    SchemaCard.Table view = card.get().tables().stream()
        .filter(t -> t.name().equals("connector.v_chatcustomer")).findFirst().orElseThrow();
    assertThat(view.columns()).extracting(SchemaCard.Column::name)
        .contains("customer_code", "name", "city");
  }

  @Test
  void ask_answers_over_the_fused_entity_view() {
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT customer_code, name, city FROM connector.v_chatcustomer ORDER BY customer_code")
        .thenReturn("Two customers: Jean Dupont in Paris, and one in Lyon.");

    AskResult r = agent.ask(7L, "clients", "list the customers");

    assertThat(r.rowCount()).isEqualTo(2);
    assertThat(r.rows().get(0)).containsEntry("customer_code", "C-1");
    assertThat(r.rows().get(0)).containsEntry("name", "Jean Dupont");
    assertThat(r.rows().get(0)).containsEntry("city", "Paris");
  }
}
