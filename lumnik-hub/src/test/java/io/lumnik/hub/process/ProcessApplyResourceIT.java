package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
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
    @Inject EntityRegistry entities;

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
        entities.remove("procfused");   // an earlier test may have cached another definition under this name
        // Being in the card is not enough — the trigger table must be one a row writer fills.
        // t_proc_demo is a DynamicTableWriter target; ext_proc_hybrid is a HybridJsonbWriter one,
        // registered under the `hybrid:` marker — both stage data.row.changed.
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 "
            + "AND mapping_id IN ('proc-demo', 'proc-crm', 'hybrid:ext.proc_hybrid')");
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

    /** #1830: a column operand is written {column}, quoted in a YAML flow list; the door checks the right-hand column too. */
    @Test
    void acceptsAColumnOperand_andRefusesAnUnknownRightHandColumn_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "\"code={segment}\""))
        .when().post("/api/processes/apply")
        .then().statusCode(200);

        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("segment=GOLD", "\"code={nowhere}\""))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("nowhere"));
    }

    /**
     * A decision's {@code source} is {@code process:<name>} in a VARCHAR(120) column: a longer name used to apply cleanly and then
     * lose every decision to a 22001. Refused at the door; a process already stored is still loaded.
     */
    @Test
    void refusesANameLongerThanTheSourceColumnHolds_400() {
        String longest = "x".repeat(112);
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("gold-arrive", longest))
        .when().post("/api/processes/apply")
        .then().statusCode(200);

        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("gold-arrive", longest + "x"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("metadata.name"))
            .body("error", containsString("112"));
    }

    private void registerCrm() {
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 AND mapping_id = 'proc-crm'");
        exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (1, 'proc-crm', 'connector', 't_proc_crm', '{}'::jsonb)");
    }

    /** Replaces the stored Entity (the registry's copy is dropped, so the door re-reads the row). */
    private void storeEntity(String yaml) {
        exec("DELETE FROM connector.entity_manifest WHERE name = 'procfused'");
        try (Connection c = adminDs.getConnection();
             var ps = c.prepareStatement("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES (?, ?)")) {
            ps.setString(1, "procfused");
            ps.setString(2, yaml);
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException(e); }
        entities.remove("procfused");
    }

    private ValidatableResponse applyOnFusedView() {
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.v_procfused"))
        .when().post("/api/processes/apply").then();
    }

    /**
     * #482, then #1833. A fused entity view is in the card, and nothing writes rows INTO a view. A Process on a
     * {@code match:} Entity fires on a change in the Entity's SOURCE tables (#1833), so the door asks of each source
     * what it asked of a trigger table before: that a connector's row writer fills it. A source it cannot fire on
     * would leave the process silent for that source for ever.
     */
    @Test
    void refusesAProcessOnAnEntityWhoseSourceCannotFire_400() {
        applyOnFusedView().statusCode(400)
            .body("error", containsString("connector.t_proc_crm"))
            .body("error", containsString("connector.v_procfused"))
            .body("error", containsString("data.row.changed"));

        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().statusCode(200).body("name", not(hasItem("gold-arrive")));
    }

    @Test
    void appliesAProcessOnAMatchEntityWhoseSourcesAllFire() {
        registerCrm();
        applyOnFusedView().statusCode(200);

        given().header("X-Tenant-Id", "1").when().get("/api/processes")
        .then().statusCode(200).body("name", hasItem("gold-arrive"));
    }

    private static final String ENTITY_WITH_JOINS = """
        kind: Entity
        name: ProcFused
        match: [code]
        scopes: [procdesk]
        sources:
          - table: t_proc_demo
            map: { code: code, segment: segment }
        joins:
          - table: t_proc_crm
            from: code
            to: code
            map: { segment: crm_segment }
        """;

    /** #1834: a lookup change fans out to the fused rows, so an Entity with {@code joins:} is served. */
    @Test
    void appliesAProcessOnAnEntityWithJoins() {
        registerCrm();
        storeEntity(ENTITY_WITH_JOINS);
        applyOnFusedView().statusCode(200);
    }

    /** A lookup nothing writes events for would never re-evaluate the rows it reaches: said at apply, with the table named. */
    @Test
    void refusesAProcessOnAnEntityWhoseLookupCannotFire_400() {
        storeEntity(ENTITY_WITH_JOINS);   // t_proc_crm is not a table a connector registered
        applyOnFusedView().statusCode(400)
            .body("error", containsString("joins connector.t_proc_crm"))
            .body("error", containsString("data.row.changed"));
    }

    /** Another node re-applied the Entity as a resolution one; this node's registry still holds the old shape. The door decides on the stored row. */
    @Test
    void decidesOnTheStoredEntity_notOnThisNodesCopy() {
        registerCrm();
        applyOnFusedView().statusCode(200);   // loads the plain Entity into the registry
        exec("UPDATE connector.entity_manifest SET yaml_text = $y$"
            + """
            kind: Entity
            name: ProcFused
            scopes: [procdesk]
            sources:
              - table: t_proc_demo
                map: { code: code, segment: segment }
              - table: t_proc_crm
                map: { code: code, segment: segment }
            resolution:
              identity:
                t_proc_demo: code
                t_proc_crm: code
              cascade:
                - id: by-code
                  exact: { fields: [code] }
            """
            + "$y$ WHERE name = 'procfused'");   // the registry is NOT told

        applyOnFusedView().statusCode(400).body("error", containsString("resolution:"));
    }

    /**
     * #1846: an Entity's view is in the cards of the Entity's own {@code scopes:} only, and the door asks the card of the
     * Process's scope for {@code on.table}. A Process of one scope cannot name the view of an Entity of another, so its
     * decisions can never carry fused fields of rows outside its scope.
     */
    @Test
    void refusesAProcessOnTheViewOfAnEntityThatIsNotInItsScope_400() {
        registerCrm();
        List<SchemaCard.Column> cols = List.of(new SchemaCard.Column("code", "text", "Code"));
        store.save(1L, "registry", new SchemaCard("procdesk", List.of(
            new SchemaCard.Table("connector.t_proc_demo", "Demo", cols))));   // the Entity is declared for another scope

        applyOnFusedView().statusCode(400).body("error", containsString("is not in the 'procdesk' scope"));
    }

    /** #1846: a stored Entity another version wrote and this one cannot read is a refusal that says so, not a 500. */
    @Test
    void refusesAProcessWhenTheStoredEntityCannotBeRead_400() {
        registerCrm();
        storeEntity("kind: Entity\nname: ProcFused\nmatch: [code\n");

        applyOnFusedView().statusCode(400).body("error", containsString("procfused"));
    }

    @Test
    void refusesAProcessOnAResolutionEntity_400() {
        registerCrm();
        storeEntity("""
            kind: Entity
            name: ProcFused
            scopes: [procdesk]
            sources:
              - table: t_proc_demo
                map: { code: code, segment: segment }
              - table: t_proc_crm
                map: { code: code, segment: segment }
            resolution:
              identity:
                t_proc_demo: code
                t_proc_crm: code
              cascade:
                - id: by-code
                  exact: { fields: [code] }
            """);
        applyOnFusedView().statusCode(400)
            .body("error", containsString("resolution:"))
            .body("error", containsString("procfused"));
    }

    @Test
    void validatesTheKeyAndTheConditionsAgainstTheEntityCard() {
        registerCrm();
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.v_procfused")
                .replace("emit: {", "emit: { key: nowhere,"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("spec.emit.key names nowhere"))
            .body("error", containsString("connector.v_procfused"));

        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "connector.v_procfused")
                .replace("segment=GOLD", "\"code={nowhere}\""))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("nowhere"));
    }

    /**
     * #1816: the hybrid writer stages data.row.changed now, so a Process on an {@code ext.*} target a
     * connector run registered is accepted.
     */
    @Test
    void appliesAProcessOnARegisteredHybridTarget() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "ext.proc_hybrid"))
        .when().post("/api/processes/apply")
        .then().statusCode(200).body("table", is("ext.proc_hybrid"));
    }

    /**
     * #482, the shape the first fix waved through: a card can carry a table no connector ever
     * registered. The guard asks the positive question — is there a registry row — so a name
     * nothing writes into is refused.
     */
    @Test
    void refusesAProcessOnAnExtNameNothingRegistered_400() {
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 AND mapping_id = 'hybrid:ext.proc_hybrid'");
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST.replace("connector.t_proc_demo", "ext.proc_hybrid"))
        .when().post("/api/processes/apply")
        .then().statusCode(400)
            .body("error", containsString("ext.proc_hybrid"))
            .body("error", containsString("cannot fire"))
            // A hybrid table lives in the schema its target names, not only in ext (#1816).
            .body("error", containsString(
                "a hybrid table, <schema>.<name>, usually ext.<name> (a REST endpoint's target or a one-shot ingest)"));
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
