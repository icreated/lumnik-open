package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.ExportManifestFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ExportApplyResourceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    // cust_ref -> customer-id: a hyphenated canonical column, quoted (not character-restricted)
    // by EntityViewCompiler — a real entity can legitimately declare one, and this fixture proves
    // ExportManifestParser doesn't wrongly refuse it on shape alone (see the test below).
    private static final String ENTITY = """
        kind: Entity
        name: ExportApplyEntity676
        match: [code]
        scopes: [sales]
        sources:
          - table: t_exportapply_erp
            map:
              cust_id: code
              cust_name: name
              cust_city: city
              cust_ref: customer-id
        """;

    @BeforeEach
    void applyFixtureEntity() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_exportapply_erp", "v_exportapplyentity676",
            "cust_id", "cust_name", "cust_city", "cust_ref");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    private static String manifest(String name, String scope, String entity, String columns) {
        return ExportManifestFixture.sftpManifest(name, scope, entity, columns);
    }

    @Test
    void aValidManifestRoundTripsPreservingDeclaredColumnOrder() {
        String name = "export-roundtrip-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "ExportApplyEntity676", "city, code, name"))
            .post("/api/exports/apply")
            .then().statusCode(200)
            .body("name", equalTo(name))
            .body("scope", equalTo("sales"))
            .body("entity", equalTo("ExportApplyEntity676"));

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/" + name)
            .then().statusCode(200)
            .body(containsString("columns: [city, code, name]"));
    }

    /**
     * EntityRegistry resolves case-insensitively, and the store persists the CANONICAL spelling
     * (entity.name()) precisely so two exports can't end up naming one entity two ways. The apply
     * response/audit must report that same canonical spelling — not the caller's raw
     * spec.source.entity text — or `apply` and `list`/`get` would show two different spellings for
     * the same entity.
     */
    @Test
    void applyResponseReportsTheCanonicalEntitySpellingNotTheCallersCasing() {
        String name = "export-canonical-entity-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "exportapplyentity676", "code"))
            .post("/api/exports/apply")
            .then().statusCode(200)
            .body("entity", equalTo("ExportApplyEntity676"));
    }

    @Test
    void scopeNotDeclaredByTheEntityIsRefused() {
        String name = "export-badscope-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "not-a-declared-scope", "ExportApplyEntity676", "code"))
            .post("/api/exports/apply")
            .then().statusCode(400)
            .body("error", containsString("declared scopes"));
    }

    @Test
    void columnNotOnTheEntityIsRefused() {
        String name = "export-badcolumn-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "ExportApplyEntity676", "code, not_a_real_column"))
            .post("/api/exports/apply")
            .then().statusCode(400)
            .body("error", containsString("'not_a_real_column' is not a canonical column"));
    }

    @Test
    void aShapedManifestIsAcceptedAndStoredAsWritten() {
        String name = "export-shaped-" + System.nanoTime();
        String yaml = manifest(name, "sales", "ExportApplyEntity676",
                "code, {name: city, as: VILLE, format: [{lookup: {table: {Lyon: LYS}}}, {mask: {keep-first: 1}}]}")
            .replace("  order: [code]", "  filter: [{column: name, in: [Ann, Bob]}]\n  order: [code]");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
            .post("/api/exports/apply")
            .then().statusCode(200);

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/" + name)
            .then().statusCode(200)
            .body(containsString("as: VILLE"))
            .body(containsString("filter: [{column: name, in: [Ann, Bob]}]"));
    }

    @Test
    void anUnknownFormatterIsRefusedNamingItsPath() {
        String name = "export-badformatter-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "ExportApplyEntity676", "code, {name: name, format: [{format-dat: {}}]}"))
            .post("/api/exports/apply")
            .then().statusCode(400)
            .body("error", containsString("spec.columns[1].format[0]: unknown formatter 'format-dat'"));
    }

    @Test
    void aFilterOnAColumnTheEntityLacksIsRefused() {
        String name = "export-badfilter-" + System.nanoTime();
        String yaml = manifest(name, "sales", "ExportApplyEntity676", "code")
            .replace("  order: [code]", "  filter: [{column: status, equals: A}]\n  order: [code]");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
            .post("/api/exports/apply")
            .then().statusCode(400)
            .body("error", containsString(
                "spec.filter[0].column: 'status' is not a canonical column of entity 'ExportApplyEntity676'"));
    }

    /** The end-to-end proof for the fixture's hyphenated column: a real entity can legitimately
     *  declare one, EntityViewCompiler quotes rather than restricts it, and the apply door must
     *  accept it via the entity.canonicalColumns() cross-check — not refuse it on shape alone. */
    @Test
    void aHyphenatedCanonicalColumnOnARealEntityIsAccepted() {
        String name = "export-hyphenated-column-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "ExportApplyEntity676", "code, customer-id"))
            .post("/api/exports/apply")
            .then().statusCode(200)
            .body("name", equalTo(name));
    }

    @Test
    void unknownEntityIsRefused() {
        String name = "export-noentity-" + System.nanoTime();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(manifest(name, "sales", "NoSuchEntity676", "code"))
            .post("/api/exports/apply")
            .then().statusCode(400)
            .body("error", containsString("no entity named 'NoSuchEntity676'"));
    }

    // ---- malformed YAML documents must refuse cleanly (400), never crash (500). readTree()
    // called a null/non-object root is what ExportApplyResource.refuseInlineCredentials() would
    // dereference first, before parse()'s own field-by-field checks ever run. ----

    @Test
    void emptyBodyIsRefusedNotA500() {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body("")
            .post("/api/exports/apply")
            .then().statusCode(400);
    }

    @Test
    void explicitNullDocumentBodyIsRefusedNotA500() {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body("null")
            .post("/api/exports/apply")
            .then().statusCode(400);
    }

    @Test
    void rootArrayBodyIsRefusedNotA500() {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body("- one\n- two\n")
            .post("/api/exports/apply")
            .then().statusCode(400);
    }

    @Test
    void rootScalarBodyIsRefusedNotA500() {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body("just a string")
            .post("/api/exports/apply")
            .then().statusCode(400);
    }

    @Test
    void scheduleRoundTrips() {
        String name = "export-schedule-" + System.nanoTime();
        String yaml = manifest(name, "sales", "ExportApplyEntity676", "code")
            .replace("mode: snapshot", "mode: snapshot\n  schedule: \"0 3 * * *\"");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
            .post("/api/exports/apply")
            .then().statusCode(200);

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.schedule", equalTo("0 3 * * *"));
    }
}
