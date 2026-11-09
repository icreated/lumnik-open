package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * #1881: a LIMS registers a sample at 00:30 Paris, the ERP's goods receipt for the batch is dated that day. In
 * Paris they agree; at the UTC day (an entity with no zone) the sample is on the day before. The same two tables
 * are fused by two entities, one declaring {@code zone: Europe/Paris}, one not.
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class EntityZoneIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String SOURCES = """
      match: [batch_no]
      scopes: [zn]
      sources:
        - table: t_zn_erp
          map: { no: batch_no, received: received }
        - table: t_zn_lims
          map: { code: batch_no, received_at: received }
      """;

  static final String PARIS = "kind: Entity\nname: ZnParis\nzone: Europe/Paris\n" + SOURCES;
  // Every source a date: the column stays a PostgreSQL date, whatever the zone.
  static final String DAYS = """
      kind: Entity
      name: ZnDays
      zone: Europe/Paris
      match: [batch_no]
      scopes: [zn]
      sources:
        - table: t_zn_erp
          map: { no: batch_no, received: received }
      """;
  static final String UTC = "kind: Entity\nname: ZnUtc\n" + SOURCES;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_znparis\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_znutc\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_zndays\"");
      st.execute("DROP TABLE IF EXISTS connector.t_zn_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_zn_lims");
      st.execute("CREATE TABLE connector.t_zn_erp (tenant_id bigint NOT NULL, \"no\" text, received date)");
      st.execute("CREATE TABLE connector.t_zn_lims (tenant_id bigint NOT NULL, code text, received_at timestamptz)");
      for (String t : new String[]{"t_zn_erp", "t_zn_lims"}) {
        st.execute("DO $$ BEGIN GRANT SELECT ON connector." + t + " TO lumnik_app;"
            + " GRANT SELECT ON connector." + t + " TO lumnik_readonly;"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
      }
      // L-102: 23:40 Paris on the 5th, the ERP says the 5th — they agree at either day.
      st.execute("INSERT INTO connector.t_zn_erp VALUES (1,'L-102','2026-11-05')");
      st.execute("INSERT INTO connector.t_zn_lims VALUES (1,'L-102','2026-11-05 22:40+00')");
      // L-104: 00:30 Paris on the 6th (23:30 UTC on the 5th), the ERP says the 6th — they agree in Paris only.
      st.execute("INSERT INTO connector.t_zn_erp VALUES (1,'L-104','2026-11-06')");
      st.execute("INSERT INTO connector.t_zn_lims VALUES (1,'L-104','2026-11-05 23:30+00')");
      // L-105: the ERP says the 7th, the sample is on the 6th in Paris — a real disagreement, in any zone.
      st.execute("INSERT INTO connector.t_zn_erp VALUES (1,'L-105','2026-11-07')");
      st.execute("INSERT INTO connector.t_zn_lims VALUES (1,'L-105','2026-11-05 23:30+00')");
      // L-106: only the ERP knows it, a date — its midnight is the evening of the 7th in Paris, the 8th in UTC.
      st.execute("INSERT INTO connector.t_zn_erp VALUES (1,'L-106','2026-11-08')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name IN ('znparis', 'znutc', 'zndays')");
    }
    for (String manifest : new String[]{PARIS, UTC, DAYS}) {
      given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
          .contentType("application/yaml").body(manifest)
          .post("/api/entities/apply").then().statusCode(200);
    }
  }

  @Test
  void aTimeAfterLocalMidnightAgreesWithItsDateInTheDeclaredZone() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/znparis?batch_no=L-104")
        .then().statusCode(200)
        .body("[0]._conflicts", equalTo("{}"));
  }

  @Test
  void withoutAZoneTheDayIsTheUtcOne() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/znutc?batch_no=L-104")
        .then().statusCode(200)
        .body("[0]._conflicts", containsString("\"received\""));
  }

  @Test
  void anEveningTimeStillAgreesInBothZones() {
    for (String entity : new String[]{"znparis", "znutc"}) {
      given().headers(TestIdentity.tenantAndUser(1))
          .get("/api/entities/" + entity + "?batch_no=L-102")
          .then().statusCode(200)
          .body("[0]._conflicts", equalTo("{}"));
    }
  }

  @Test
  void aRealDisagreementIsStillOneInTheDeclaredZone() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/znparis?batch_no=L-105")
        .then().statusCode(200)
        .body("[0]._conflicts", containsString("\"received\""));
  }

  @Test
  void theConflictsDoorListsOnlyWhatDisagreesAtTheBusinessDay() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("find { it.entity == 'ZnParis' && it.key == 'L-104' }", org.hamcrest.Matchers.nullValue())
        .body("find { it.entity == 'ZnParis' && it.key == 'L-105' }", not(org.hamcrest.Matchers.nullValue()))
        .body("find { it.entity == 'ZnUtc' && it.key == 'L-104' }", not(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void aDayFilterIsTheBusinessDayOfTheEntity() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/znparis?received=2026-11-08")
        .then().statusCode(200)
        .body("batch_no", org.hamcrest.Matchers.contains("L-106"));
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/znutc?received=2026-11-08")
        .then().statusCode(200)
        .body("batch_no", org.hamcrest.Matchers.contains("L-106"));
  }

  @Test
  void aDayFilterOnAColumnThatStaysADateIsThatDate() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/zndays?received=2026-11-08")
        .then().statusCode(200)
        .body("batch_no", org.hamcrest.Matchers.contains("L-106"));
  }
}
