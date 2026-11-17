package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.RegisteredTables;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/** #1974: a point is two numeric columns of the fused view; the card says so, and only then. */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)   // reused: no new application for this class
class EntityPointsIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntitySchemaBuilder entityCards;
  @Inject SchemaCardStore cards;

  static final String MANIFEST = """
      kind: Entity
      name: GeoSite
      match: [site_no]
      scopes: [geo]
      sources:
        - table: t_geo_sites
          map: { no: site_no, latitude: latitude, longitude: longitude, lat_text: lat_text }
      points:
        - name: site
          lat: %s
          lon: longitude
      """;

  @BeforeEach
  void seed() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_geosite\"");
      st.execute("DROP TABLE IF EXISTS connector.t_geo_sites");
      st.execute("CREATE TABLE connector.t_geo_sites (tenant_id bigint NOT NULL, \"no\" text,"
          + " latitude numeric, longitude numeric, lat_text text)");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'geosite'");
    }
    RegisteredTables.register(adminDs, 1L, "t_geo_sites");
  }

  private ValidatableResponse apply(String lat) {
    return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST.formatted(lat))
        .post("/api/entities/apply").then();
  }

  @Test
  void aPointOnTwoNumbersIsOnTheCard() {
    apply("latitude").statusCode(200);

    assertThat(entityCards.resolve(1L, "geo")).singleElement()
        .satisfies(t -> assertThat(t.points()).containsExactly(new SchemaCard.Point("site", "latitude", "longitude")));
  }

  @Test
  void aPointOnATextColumnIsRefusedAtApply_namingTheColumnAndItsType() {
    apply("lat_text").statusCode(400)
        .body(containsString("points[0].lat: column 'lat_text' is text, a point needs a number"))
        .body(containsString("parse-coordinates"));

    assertThat(entityCards.resolve(1L, "geo")).isEmpty();   // nothing stored: the apply is one transaction
  }

  @Test
  void aCardStoredBeforePointsExistedStillReads() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'geo-old'");
      st.execute("INSERT INTO connector.schema_card (tenant_id, scope, source_type, card) VALUES (1, 'geo-old', 'registry',"
          + " '{\"scope\":\"geo-old\",\"tables\":[{\"name\":\"connector.t_x\",\"label\":\"X\",\"columns\":"
          + "[{\"name\":\"a\",\"pgType\":\"text\",\"label\":\"A\"}],\"description\":null}]}'::jsonb)");
    }

    assertThat(cards.load(1L, "geo-old")).get()
        .satisfies(card -> assertThat(card.tables().getFirst().points()).isEmpty());
  }
}
