package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProcessApplyResourceIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 1");
        store.save(1L, "registry", new SchemaCard("procdesk", List.of(
            new SchemaCard.Table("connector.t_proc_demo", "Demo", List.of(
                new SchemaCard.Column("code", "text", "Code"),
                new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                        List.of("GOLD", "SILVER")))))));
    }

    private static final String MANIFEST = """
        apiVersion: apps.lumnik.io/v1
        kind: Process
        metadata: { name: gold-arrive }
        spec:
          scope: procdesk
          on: { event: data.row.changed, table: connector.t_proc_demo }
          when: [ segment=GOLD ]
          emit: { type: decision.gold-arrive, message: "GOLD!" }
        """;

    @Test
    void appliesAndLists() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/processes/apply")
        .then().statusCode(200)
            .body("name", is("gold-arrive"))
            .body("table", is("connector.t_proc_demo"))
            .body("emit", is("decision.gold-arrive"));

        given().header("X-Tenant-Id", "1")
        .when().get("/api/processes")
        .then().statusCode(200)
            .body("name", hasItem("gold-arrive"))
            .body("enabled", hasItem(true));
    }

    @Test
    void manifestIsRetrievable() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);

        String yaml = given().header("X-Tenant-Id", "1")
        .when().get("/api/processes/gold-arrive/manifest")
        .then().statusCode(200).extract().asString();
        Assertions.assertTrue(yaml.contains("kind: Process"), yaml);
        Assertions.assertTrue(yaml.contains("segment=GOLD"), yaml);
    }

    @Test
    void unknownManifestIs404() {
        given().header("X-Tenant-Id", "1")
        .when().get("/api/processes/nope/manifest")
        .then().statusCode(404);
    }

    @Test
    void applyIsAnUpsert() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "GOLD encore"))
            .post("/api/processes/apply").then().statusCode(200);
        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().body("findAll { it.name == 'gold-arrive' }", hasSize(1));
    }

    @Test
    void refusesAnOutOfDomainCondition_400() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "segment=PLATINUM"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("PLATINUM"))
            .body("error", containsString("GOLD, SILVER"));
    }

    @Test
    void refusesAnUnknownTriggerTable_400() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.t_ghost"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("t_ghost"));
    }

    @Test
    void unknownScope_404() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("procdesk", "ghostscope"))
        .when().post("/api/processes/apply")
        .then().statusCode(404);
    }

    @Test
    void withoutTenant_401() {
        given().config(YAML_CFG).contentType("application/yaml").body(MANIFEST)
        .when().post("/api/processes/apply")
        .then().statusCode(401);
    }

    @Test
    void refusesAMessageReferencingAnUnknownColumn_400() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "Client {segmen} arrive"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("{segmen}"))
            .body("error", containsString("connector.t_proc_demo"));
    }

    @Test
    void appliesAValidTemplatedMessage_200() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "Client {code} en {segment}"))
        .when().post("/api/processes/apply")
        .then().statusCode(200);
    }

    @Test
    void acceptsAThresholdCondition_andRefusesANonOrderedValue_400() {
        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "code>=100"))
        .when().post("/api/processes/apply")
        .then().statusCode(200);

        given().config(YAML_CFG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "segment>abc"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("not a number or ISO date"));
    }
}
