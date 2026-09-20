package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
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

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A two-source fusion whose view lands in the card beside the raw table it is built from. */
    private static final String ENTITY_YAML = """
        kind: Entity
        name: ProcFused
        match: [code]
        scopes: [procdesk]
        sources:
          - table: t_proc_demo
            map: { code: code, segment: segment }
          - table: t_proc_crm
            map: { code: code, segment: segment }
        """;

    @BeforeEach
    void seed() {
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 1");
        exec("DELETE FROM connector.entity_manifest WHERE name = 'procfused'");
        // Being in the card is not enough — the trigger table must be one a row writer fills.
        // t_proc_demo is a DynamicTableWriter target; ext_proc_hybrid is a HybridJsonbWriter one,
        // registered for discovery under the `hybrid:` marker and writing no events.
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 "
            + "AND mapping_id IN ('proc-demo', 'hybrid:ext.proc_hybrid')");
        exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (1, 'proc-demo', 'connector', 't_proc_demo', '{}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (1, 'hybrid:ext.proc_hybrid', 'ext', 'proc_hybrid', '{}'::jsonb)");
        try (Connection c = adminDs.getConnection();
             var ps = c.prepareStatement(
                 "INSERT INTO connector.entity_manifest (name, yaml_text) VALUES (?, ?)")) {
            ps.setString(1, "procfused");
            ps.setString(2, ENTITY_YAML);
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException(e); }
        // No preload: the apply door goes through EntityRegistry.findReloadingIfMissing, the very
        // path that must see a manifest a second hub instance wrote.

        List<SchemaCard.Column> cols = List.of(
            new SchemaCard.Column("code", "text", "Code"),
            new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                    List.of("GOLD", "SILVER")));
        store.save(1L, "registry", new SchemaCard("procdesk", List.of(
            new SchemaCard.Table("connector.t_proc_demo", "Demo", cols),
            // The fused view is advertised beside the raw table — EntitySchemaBuilder puts it there.
            new SchemaCard.Table("connector.v_procfused", "ProcFused", cols),
            // …and so is the hybrid target — RegistrySchemaBuilder reads every dynamic_table row.
            new SchemaCard.Table("ext.proc_hybrid", "Hybrid", cols))));
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
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
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
    void refusesAnUnknownKeyWithTheHonoredSurface() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("scope: procdesk", "scope: procdesk\n  scop: x"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", equalTo("unknown key(s) in the manifest: spec.scop — kind:Process honors "
                + ProcessManifestParser.honoredSurface()));
    }

    @Test
    void manifestIsRetrievable() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
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
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "GOLD encore"))
            .post("/api/processes/apply").then().statusCode(200);
        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().body("findAll { it.name == 'gold-arrive' }", hasSize(1));
    }

    @Test
    void refusesAnOutOfDomainCondition_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "segment=PLATINUM"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("PLATINUM"))
            .body("error", containsString("GOLD, SILVER"));
    }

    @Test
    void refusesAnUnknownTriggerTable_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.t_ghost"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("t_ghost"));
    }

    @Test
    void unknownScope_404() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("procdesk", "ghostscope"))
        .when().post("/api/processes/apply")
        .then().statusCode(404);
    }

    @Test
    void withoutTenant_401() {
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(MANIFEST)
        .when().post("/api/processes/apply")
        .then().statusCode(401);
    }

    @Test
    void refusesAMessageReferencingAnUnknownColumn_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "Client {segmen} arrive"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("{segmen}"))
            .body("error", containsString("connector.t_proc_demo"));
    }

    @Test
    void appliesAValidTemplatedMessage_200() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("GOLD!", "Client {code} en {segment}"))
        .when().post("/api/processes/apply")
        .then().statusCode(200);
    }

    @Test
    void acceptsAThresholdCondition_andRefusesANonOrderedValue_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "code>=100"))
        .when().post("/api/processes/apply")
        .then().statusCode(200);

        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "segment>abc"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("not a number or ISO date"));
    }

    /**
     * #482 — a fused entity view is in the card, so it passed the trigger-table check, returned a
     * clean 200 and sat in the list for ever: nothing writes rows into a view, so data.row.changed
     * is never born for one and the process could never fire. The refusal names the raw tables.
     */
    @Test
    void refusesAProcessOnAFusedEntityView_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.v_procfused"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("connector.v_procfused"))
            .body("error", containsString("data.row.changed"))
            .body("error", containsString("connector.t_proc_demo"))
            .body("error", containsString("connector.t_proc_crm"));

        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().statusCode(200).body("name", not(hasItem("gold-arrive")));
    }

    /**
     * #482, the shape the first fix waved through: a card carries the {@code ext.*} targets
     * HybridJsonbWriter registers for discovery, and that writer stages no outbox event at all.
     * Recognising the fused view alone let this one through — the guard asks the positive
     * question now, so anything that is not a row-writer table is refused.
     */
    @Test
    void refusesAProcessOnAHybridTargetThatWritesNoEvents_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "ext.proc_hybrid"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("ext.proc_hybrid"))
            .body("error", containsString("cannot fire"));
    }

    // ── Disable and delete: the two gestures a declared process never had (#487) ─

    @Test
    void disableStopsItFiringAndEnablePutsItBack() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);

        given().header("X-Tenant-Id", "1").contentType("application/json").body("{\"enabled\": false}")
        .when().put("/api/processes/gold-arrive/enabled")
        .then().statusCode(200).body("enabled", is(false));

        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().body("find { it.name == 'gold-arrive' }.enabled", is(false));

        given().header("X-Tenant-Id", "1").contentType("application/json").body("{\"enabled\": true}")
            .put("/api/processes/gold-arrive/enabled").then().statusCode(200);
        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().body("find { it.name == 'gold-arrive' }.enabled", is(true));
    }

    @Test
    void deleteRemovesItAndTheSecondDeleteIs404() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);

        given().header("X-Tenant-Id", "1").when().delete("/api/processes/gold-arrive")
        .then().statusCode(204);

        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().body("findAll { it.name == 'gold-arrive' }", hasSize(0));

        // An unknown name is the same 404 the manifest door answers — one boundary, one word.
        given().header("X-Tenant-Id", "1").when().delete("/api/processes/gold-arrive")
        .then().statusCode(404).body("error", containsString("gold-arrive"));
    }

    @Test
    void enabledWithoutABody_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
            .post("/api/processes/apply").then().statusCode(200);
        given().header("X-Tenant-Id", "1").contentType("application/json").body("{}")
        .when().put("/api/processes/gold-arrive/enabled")
        .then().statusCode(400).body("error", containsString("enabled"));
    }

    @Test
    void deleteWithoutTenant_401() {
        given().when().delete("/api/processes/gold-arrive").then().statusCode(401);
    }
}
