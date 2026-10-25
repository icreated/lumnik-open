package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.DiscoveryService;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EntityRagChatIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject SchemaCardStore store;
  @Inject DiscoveryService discovery;
  @Inject AskToSqlAgent agent;
  @InjectMock @Analytic ChatPort chatPort;

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
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(7))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  /** Another tenant holding the shared entity: it never applied it, its card still lists the view. */
  static final long OTHER_TENANT = 8L;

  @AfterEach
  void dropTheCardsThisClassLeft() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.schema_card WHERE (tenant_id = 7 AND scope = 'ventes') "
          + "OR (tenant_id = " + OTHER_TENANT + " AND scope IN ('clients', 'ventes'))");
    }
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

  /**
   * An entity is shared by every tenant, and a re-apply into another scope moves it for all of them:
   * no card, in the scope it left or in another tenant, keeps listing it.
   */
  @Test
  void a_re_apply_into_another_scope_takes_the_view_off_every_card_it_left() {
    holdTheEntityInAnotherTenant();

    reapply(MANIFEST.replace("scopes: [clients]", "scopes: [ventes]"));

    assertThat(tablesOf(7L, "clients")).doesNotContain("connector.v_chatcustomer");
    assertThat(tablesOf(7L, "ventes")).contains("connector.v_chatcustomer");
    afterTheRequest(() -> assertThat(tablesOf(OTHER_TENANT, "clients")).doesNotContain("connector.v_chatcustomer"));
  }

  /** A column the re-apply dropped leaves every tenant's card, not only the applying tenant's. */
  @Test
  void a_re_apply_dropping_a_column_refreshes_the_card_of_another_tenant() {
    holdTheEntityInAnotherTenant();

    reapply(MANIFEST.replace("      ville: city\n", ""));

    afterTheRequest(() -> assertThat(columnsOf(OTHER_TENANT, "clients")).contains("customer_code").doesNotContain("city"));
  }

  /** A delete takes the view off every tenant's card. */
  @Test
  void a_delete_takes_the_view_off_the_card_of_another_tenant() {
    holdTheEntityInAnotherTenant();

    given().headers(TestIdentity.tenantAndUser(7))
        .delete("/api/entities/chatcustomer").then().statusCode(204);

    assertThat(tablesOf(7L, "clients")).doesNotContain("connector.v_chatcustomer");
    afterTheRequest(() -> assertThat(tablesOf(OTHER_TENANT, "clients")).doesNotContain("connector.v_chatcustomer"));
  }

  private void holdTheEntityInAnotherTenant() {
    TenantContext.callAsSystem(() -> discovery.rediscover(OTHER_TENANT, "clients"));
    assertThat(columnsOf(OTHER_TENANT, "clients")).contains("city");
  }

  /** Another tenant's card is rebuilt after the request answers (it may read that tenant's source). */
  private static void afterTheRequest(Runnable assertion) {
    await().atMost(Duration.ofSeconds(10)).untilAsserted(assertion::run);
  }

  private static void reapply(String manifest) {
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(7))
        .contentType("application/yaml").body(manifest)
        .post("/api/entities/apply").then().statusCode(200);
  }

  /** A scope with nothing left to describe keeps no card: no card lists nothing. */
  private List<String> tablesOf(long tenant, String scope) {
    return store.load(tenant, scope).map(SchemaCard::tableNames).orElse(List.of());
  }

  private List<String> columnsOf(long tenant, String scope) {
    return store.load(tenant, scope).stream()
        .flatMap(c -> c.tables().stream())
        .filter(t -> t.name().equals("connector.v_chatcustomer"))
        .flatMap(t -> t.columns().stream())
        .map(SchemaCard.Column::name)
        .toList();
  }
}
