package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * #2057: a map view renders the hub's verdict per row ({@code _map}), the count of rows it could not place, and never
 * the point's columns the view does not show — not in the rows, not in {@code columns}, not in the detail.
 */
@QuarkusTest
class ViewMapRenderIT {

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final List<SchemaCard.Column> COLUMNS = List.of(
        new SchemaCard.Column("customer_no", "text", "No"),
        new SchemaCard.Column("name", "text", "Name"),
        new SchemaCard.Column("latitude", "numeric", "Latitude"),
        new SchemaCard.Column("longitude", "numeric", "Longitude"));

    private void card(List<SchemaCard.Point> points) {
        store.save(1L, "registry", new SchemaCard("geomap", List.of(
            new SchemaCard.Table("connector.t_view_map_demo", "Customers", COLUMNS, null, points))));
    }

    @BeforeEach
    void seed() {
        // A t_* table carries an id, as every ingested one does: the paged read keys on it.
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_map_demo "
           + "(id bigserial, customer_no text, name text, latitude numeric, longitude numeric)");
        exec("TRUNCATE connector.t_view_map_demo");
        exec("INSERT INTO connector.t_view_map_demo (customer_no, name, latitude, longitude) VALUES "
           + "('C-1','Lyon',45.7640,4.8357), ('C-2','Nowhere',NULL,4.8), ('C-3','Beyond',95,4.8),"
           + "('C-4','Sydney',-33.8688,151.2093)");
        card(List.of(new SchemaCard.Point("site", "latitude", "longitude")));
    }

    private static final String MAP_VIEW = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: customers-map }
        spec:
          scope: geomap
          table: connector.t_view_map_demo
          columns: [customer_no, name]
          map: { point: site }
        """;

    private static ValidatableResponse render(String yaml, String query) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(yaml)
            .when().post("/api/views/render" + query).then();
    }

    @Test
    void eachRowCarriesTheHubsVerdictAndTheUnplacedAreCounted() {
        render(MAP_VIEW, "").statusCode(200)
            .body("map.point", equalTo("site"))
            .body("map.unplaced", equalTo(2))
            .body("map", not(hasKey("error")))
            .body("rows.customer_no", contains("C-1", "C-2", "C-3", "C-4"))
            .body("rows[0]._map.lat", equalTo(45.764f))
            .body("rows[0]._map.lon", equalTo(4.8357f))
            .body("rows[1]", hasKey("_map"))
            .body("rows[1]._map", nullValue())
            .body("rows[2]._map", nullValue())
            .body("rows[3]._map.lat", equalTo(-33.8688f));
    }

    @Test
    void thePointsColumnsTheViewDoesNotShowAreNotServed() {
        render(MAP_VIEW, "").statusCode(200)
            .body("columns.name", contains("customer_no", "name"))
            .body("rows[0]", not(hasKey("latitude")))
            .body("rows[0]", not(hasKey("longitude")));
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MAP_VIEW)
            .when().post("/api/views/detail?key=C-1")
            .then().statusCode(200)
            .body(not(containsString("latitude")))
            .body(not(containsString("45.764")));
    }

    @Test
    void thePagedReadCarriesTheSameVerdict() {
        render(MAP_VIEW, "?pageSize=2").statusCode(200)
            .body("rows.customer_no", contains("C-1", "C-2"))
            .body("rows[0]._map.lat", equalTo(45.764f))
            .body("map.unplaced", equalTo(1));
    }

    @Test
    void aPointNoLongerOnTheCardRendersTheListWithTheReason() {
        card(List.of());
        render(MAP_VIEW, "").statusCode(200)
            .body("rows.customer_no", contains("C-1", "C-2", "C-3", "C-4"))
            .body("rows[0]", not(hasKey("_map")))
            .body("map.point", equalTo("site"))
            .body("map.error", containsString("point 'site' is not usable on connector.t_view_map_demo now"));
    }

    @Test
    void aViewWithoutAMapAnswersWithoutOne() {
        render(MAP_VIEW.replace("  map: { point: site }\n", ""), "").statusCode(200)
            .body("$", not(hasKey("map")))
            .body("rows[0]", not(hasKey("_map")));
    }
}
