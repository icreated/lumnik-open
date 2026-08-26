package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ViewRenderResourceIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    @Inject SchemaCardStore store;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_render_demo "
           + "(customer_code text, name text, segment text)");
        exec("TRUNCATE connector.t_view_render_demo");
        exec("INSERT INTO connector.t_view_render_demo VALUES "
           + "('C-1','Alice','GOLD'), ('C-2','Bob','SILVER')");

        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_render_demo", "Desk", List.of(
                new SchemaCard.Column("customer_code", "text", "Code"),
                new SchemaCard.Column("name", "text", "Name"),
                new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                        List.of("GOLD", "SILVER")))))));
    }

    private static final String MANIFEST = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: desk-view }
        spec:
          scope: desk
          table: connector.t_view_render_demo
          title: Desk
          columns: [customer_code, name]
        """;

    private static final String MANIFEST_WITH_SEGMENT =
        MANIFEST.replace("[customer_code, name]", "[customer_code, name, segment]");

    @Test
    void rendersTitleColumnsAndRows() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("title", is("Desk"))
            .body("columns.name", contains("customer_code", "name"))
            .body("columns.label", contains("Code", "Name"))
            .body("rows.customer_code", contains("C-1", "C-2"));
    }

    @Test
    void renderCarriesTheScopesLastCompletedRunAsDataAsOf() {
        // P6: the Fiches freshness dial. Same signal as the ask (ScopeFreshness):
        // the scope's last completed run — absent when none, never fabricated.
        exec("DELETE FROM connector.connector_run WHERE tenant_id = 1");
        exec("DELETE FROM connector.connector WHERE tenant_id = 1 AND name = 'src-vr-asof'");
        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('00000000-0000-0000-0000-00000000b6f1', 1, 'm_vr_asof', 'public.x', 'yaml', 'x') "
           + "ON CONFLICT DO NOTHING");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, enabled, tags, sync_mode, skip_policy) "
           + "VALUES ('00000000-0000-0000-0000-00000000b6f2', 1, 'src-vr-asof', 'csv-file', '{}'::jsonb, "
           + "'00000000-0000-0000-0000-00000000b6f1', true, ARRAY['desk'], 'Incremental', '{\"type\":\"SkipUpTo\",\"max\":1}'::jsonb)");
        exec("INSERT INTO connector.connector_run (tenant_id, connector_id, status, sync_mode, triggered_by, started_at, ended_at) "
           + "VALUES (1, '00000000-0000-0000-0000-00000000b6f2', 'Completed', 'Incremental', 'test', "
           + "'2026-08-21T05:00:00Z', '2026-08-21T05:04:00Z')");

        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("dataAsOf", startsWith("2026-08-21T05:04"));
    }

    @Test
    void refusesAnUnknownColumn_400() {
        String bad = MANIFEST.replace("[customer_code, name]", "[customer_code, nope]");
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(bad)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("nope"));
    }

    @Test
    void unknownScope_404() {
        String ghost = MANIFEST.replace("scope: desk", "scope: ghost");
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(ghost)
        .when().post("/api/views/render")
        .then().statusCode(404);
    }

    @Test
    void withoutTenantInContext_401() {
        given().config(YAML_CFG).contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(401);
    }

    @Test
    void filtersRowsWithEqualityAndContains() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "name~ali")
            .body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.customer_code", contains("C-1"));
    }

    @Test
    void refusesAFilterOnAColumnTheViewDoesNotShow_400() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment=GOLD")   // MANIFEST does not show segment
            .body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("segment"));
    }

    @Test
    void refusesAnOutOfDomainValue_400_withTheAllowedList() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment=PLATINUM")
            .body(MANIFEST_WITH_SEGMENT)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("PLATINUM"))
            .body("error", containsString("GOLD, SILVER"));
    }

    @Test
    void containsOnADomainColumnIsASearchNotAnAssertion() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment~GO")
            .body(MANIFEST_WITH_SEGMENT)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.customer_code", contains("C-1"));
    }
}
