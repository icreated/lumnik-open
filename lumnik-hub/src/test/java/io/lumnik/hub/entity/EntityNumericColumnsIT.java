package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.Disputed;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A number carried by every source stays a number once fused (#1338) — the #1325 rule for dates applied
 * to numbers. Whole sources fuse as {@code bigint}; one decimal source makes the column {@code numeric},
 * read without trailing zeros, so {@code 7} and {@code 7.0000} are one value: one entity, no conflict.
 * A number beside text stays text. The column is then ordered and compared as a number, while every
 * read still writes its value as a string.
 *
 * <p>{@code t_nu_erp} plays the ERP (typed numbers), {@code t_nu_csv} a CSV whose numbers were parsed
 * ({@code numeric(18,4)} for a decimal, {@code bigint} for {@code integer: true}), with one column left as
 * written.
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class EntityNumericColumnsIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntitySchemaBuilder entityCards;
  @Inject SchemaCardStore cards;
  @Inject AskToSqlAgent agent;
  @InjectMock @Analytic ChatPort chatPort;

  static final String MANIFEST = """
      kind: Entity
      name: NuOrder
      match: [order_no]
      scopes: [nu]
      sources:
        - table: t_nu_erp
          map: { no: order_no, amount: amount, qty: qty, loose: loose }
        - table: t_nu_csv
          map: { code: order_no, amount: amount, qty: qty, loose: loose }
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_nuorder\"");
      st.execute("DROP TABLE IF EXISTS connector.t_nu_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_nu_csv");
      st.execute("CREATE TABLE connector.t_nu_erp (tenant_id bigint NOT NULL, \"no\" int8,"
          + " amount numeric(18,4), qty int4, loose int8)");
      st.execute("CREATE TABLE connector.t_nu_csv (tenant_id bigint NOT NULL, code numeric(18,4),"
          + " amount int8, qty int8, loose text)");
      for (String t : new String[]{"t_nu_erp", "t_nu_csv"}) {
        st.execute("DO $$ BEGIN GRANT SELECT ON connector." + t + " TO lumnik_app;"
            + " GRANT SELECT ON connector." + t + " TO lumnik_readonly;"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
      }
      // 7: the same number in both sources, written with and without a scale; `loose` differs only in writing.
      st.execute("INSERT INTO connector.t_nu_erp VALUES (1, 7, 1500.0000, 3, 10)");
      st.execute("INSERT INTO connector.t_nu_csv VALUES (1, 7.0000, 1500, 3, '10.0')");
      // 12: a real disagreement on the amount.
      st.execute("INSERT INTO connector.t_nu_erp VALUES (1, 12, 999.5000, 1, NULL)");
      st.execute("INSERT INTO connector.t_nu_csv VALUES (1, 12.0000, 998, 1, NULL)");
      // 100: one source only. As text, "100" would sort before "12" and "7".
      st.execute("INSERT INTO connector.t_nu_erp VALUES (1, 100, 20.0000, 5, NULL)");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'nuorder'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void theFusedViewKeepsANumberTypeItCanAlign() throws Exception {
    try (Connection c = adminDs.getConnection()) {
      assertThat(EntityViewCompiler.viewColumnTypes(c, "NuOrder"))
          .containsEntry("order_no", "numeric")   // int8 + numeric(18,4): a decimal, trimmed
          .containsEntry("amount", "numeric")     // numeric(18,4) + int8
          .containsEntry("qty", "bigint")         // int4 + int8: whole
          .containsEntry("loose", "text");        // int8 + text: not guessed
    }
  }

  @Test
  void sevenAndSevenPointZeroAreOneEntityAndNoConflict() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nuorder?order_no=7")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].order_no", equalTo("7"))       // a string on the wire, without a scale
        .body("[0].amount", equalTo("1500"))
        .body("[0].qty", equalTo("3"))
        .body("[0]._conflicts", not(containsString("\"amount\"")))
        .body("[0]._conflicts", containsString("\"loose\""));   // text against a number: compared as written
  }

  @Test
  void aRealDisagreementOnANumberIsStillAConflict_inItsCanonicalText() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nuorder?order_no=12")
        .then().statusCode(200)
        .body("[0].amount", equalTo("999.5"))     // the first source's value
        .body("[0]._conflicts", containsString("\"amount\""));
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("find { it.entity == 'NuOrder' && it.key == '12' }.conflicts.amount.value", contains("999.5", "998"));
  }

  @Test
  void anEntityReadOrdersAndFiltersANumberAsANumber() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nuorder")
        .then().statusCode(200).body("order_no", contains("7", "12", "100"));
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nuorder?amount=1500")
        .then().statusCode(200).body("order_no", contains("7"));
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nuorder?amount=1500.00")
        .then().statusCode(200).body("order_no", contains("7"));     // equal by value, as in a view
  }

  @Test
  void theCardTellsAskAndViewsTheRealTypes() {
    Map<String, String> card = entityCards.resolve("nu").get(0).columns().stream()
        .collect(Collectors.toMap(SchemaCard.Column::name, SchemaCard.Column::pgType));
    assertThat(card).containsEntry("order_no", "numeric").containsEntry("qty", "bigint").containsEntry("loose", "text");
  }

  @Test
  void aViewOverTheEntityOrdersAndComparesNumbers_andWritesThemAsStrings() {
    cards.save(1L, "registry", new SchemaCard("nu", entityCards.resolve("nu")));
    String view = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: nu-view }
        spec:
          scope: nu
          table: connector.v_nuorder
          columns: [order_no, amount, qty]
        """;
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(view)
        .when().post("/api/views/render")
        .then().statusCode(200)
        .body("rows.order_no", contains("7", "12", "100"))
        .body("rows.amount", contains("1500", "999.5", "20"));
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("filter", "amount>100").body(view)
        .when().post("/api/views/render")
        .then().statusCode(200).body("rows.order_no", contains("7", "12"));
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("filter", "order_no=12.0").body(view)
        .when().post("/api/views/render")
        .then().statusCode(200).body("rows.order_no", contains("12"));   // equal by value, as a number
  }

  @Test
  void aViewDetailFindsARecordByItsNumberKey() {
    cards.save(1L, "registry", new SchemaCard("nu", entityCards.resolve("nu")));
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("key", "12").body("""
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata: { name: nu-view }
            spec:
              scope: nu
              table: connector.v_nuorder
              columns: [order_no, amount]
            """)
        .when().post("/api/views/detail")
        .then().statusCode(200).body(containsString("999.5"));
  }

  /** An export reads the typed view: no text collation on a number, and its value as text. */
  @Test
  void anExportWritesANumberAsItsCanonicalText() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/exports/entities/NuOrder/download")
        .then().statusCode(200)
        .body(containsString("\"1500\""))
        .body(containsString("\"999.5\""));
  }

  /**
   * The card says numeric, so the model writes a number literal. The disputed-rows reader must still see the
   * filter — said in the canonical text the ledger writes ({@code 999.50} is {@code 999.5}) — or a count on a
   * disputed amount would pass without its warning.
   */
  @Test
  void askCountingOnADisputedNumberNamesTheDispute() {
    cards.save(1L, "nu", new SchemaCard("nu", entityCards.resolve("nu")));
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT COUNT(*) FROM connector.v_nuorder WHERE amount = 999.50")
        .thenReturn("1.");
    AskResult r = agent.ask(1L, "nu", "how many orders of 999.50?");
    assertThat(r.disputed()).singleElement().satisfies(d -> {
      assertThat(d.key()).isEqualTo("12");
      assertThat(d.column()).isEqualTo("amount");
      assertThat(d.counted()).isTrue();
      assertThat(d.sources()).extracting(Disputed.SourceValue::value).containsExactly("999.5", "998");
    });
  }

  @Test
  void askExcludingAValueASourceHoldsSaysSo() {
    cards.save(1L, "nu", new SchemaCard("nu", entityCards.resolve("nu")));
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT order_no FROM connector.v_nuorder WHERE amount = 998");
    AskResult r = agent.ask(1L, "nu", "which order is 998?");
    assertThat(r.rowCount()).isZero();
    assertThat(r.disputed()).singleElement().satisfies(d -> {
      assertThat(d.key()).isEqualTo("12");
      assertThat(d.counted()).isFalse();
      assertThat(d.matched()).isEqualTo("998");
      assertThat(d.retained()).isEqualTo("999.5");
    });
  }

  /**
   * Re-applying an entity once its sources are typed changes its view's column types. A pooled connection that had
   * prepared a read of that view server-side (pgjdbc does after five runs) would answer "cached plan must not change
   * result type" once — a 500 per connection after every such apply. {@link EntityPlanCacheInvalidator} recycles the
   * readers' pools after the apply instead (its own IT covers both pools, refusals and the connection lifecycle).
   */
  @Test
  void aReappliedEntityWhoseTypesChangeIsReadAtOnce() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_nuretyped\"");
      st.execute("DROP TABLE IF EXISTS connector.t_nu_retyped");
      st.execute("CREATE TABLE connector.t_nu_retyped (tenant_id bigint NOT NULL, \"no\" text, amount text)");
      st.execute("DO $$ BEGIN GRANT SELECT ON connector.t_nu_retyped TO lumnik_app;"
          + " GRANT SELECT ON connector.t_nu_retyped TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $$");
      st.execute("INSERT INTO connector.t_nu_retyped VALUES (1, '7', '1500')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'nuretyped'");
    }
    String manifest = "kind: Entity\nname: NuRetyped\nmatch: [no]\nsources:\n  - table: t_nu_retyped\n    map: { no: no, amount: amount }\n";
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body(manifest).post("/api/entities/apply").then().statusCode(200);
    for (int i = 0; i < 12; i++) {   // past pgjdbc's prepareThreshold on every pooled connection this hits
      given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/nuretyped").then().statusCode(200);
    }
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW connector.\"v_nuretyped\"");   // the table is created again, typed (docs/entities.md)
      st.execute("ALTER TABLE connector.t_nu_retyped ALTER COLUMN \"no\" TYPE int8 USING \"no\"::int8,"
          + " ALTER COLUMN amount TYPE numeric(18,4) USING amount::numeric");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body(manifest).post("/api/entities/apply").then().statusCode(200);
    for (int i = 0; i < 12; i++) {
      given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/nuretyped")
          .then().statusCode(200).body("[0].amount", equalTo("1500"));
    }
  }
}
