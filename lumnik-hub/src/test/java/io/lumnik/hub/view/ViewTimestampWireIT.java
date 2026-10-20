package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;

/**
 * A timestamp cell reaches the PWA and every view consumer as its UTC instant, microseconds kept:
 * the text {@code lm data row} and a message template show (#1310, #1314), not Jackson's
 * {@code …000+00:00}.
 */
@QuarkusTest
class ViewTimestampWireIT {

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final String MANIFEST = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: wire-view }
        spec:
          scope: wire
          table: connector.t_view_wire
          title: Wire
          columns: [code, at]
        """;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS connector.t_view_wire "
                + "(id BIGSERIAL PRIMARY KEY, code text, at timestamptz)");
            st.execute("TRUNCATE connector.t_view_wire");
            st.execute("INSERT INTO connector.t_view_wire (code, at) VALUES ('A', '2026-07-01 00:00:00.000500+00')");
        }
        store.save(1L, "registry", new SchemaCard("wire", List.of(
            new SchemaCard.Table("connector.t_view_wire", "Wire", List.of(
                new SchemaCard.Column("code", "text", "Code"),
                new SchemaCard.Column("at", "timestamptz", "At"))))));
    }

    @Test
    void aRenderedTimestampIsItsInstant() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.at", contains("2026-07-01T00:00:00.000500Z"));
    }

    @Test
    void aPagedTimestampIsItsInstant() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("pageSize", 10).body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.at", contains("2026-07-01T00:00:00.000500Z"));
    }

    @Test
    void aDetailTimestampIsItsInstant() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("key", "A").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(200)
            .body("fields.value", contains("A", "2026-07-01T00:00:00.000500Z"));
    }
}
