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
class ExportRegistryResourceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String ENTITY = """
        kind: Entity
        name: ExportRegistryEntity676
        match: [code]
        scopes: [sales]
        sources:
          - table: t_exportregistry_erp
            map:
              cust_id: code
        """;

    @BeforeEach
    void applyFixtureEntity() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_exportregistry_erp",
            "v_exportregistryentity676", "cust_id");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    private String applyExport(String name) {
        String manifest = ExportManifestFixture.sftpManifest(
            name, "sales", "ExportRegistryEntity676", "code");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(manifest)
            .post("/api/exports/apply")
            .then().statusCode(200);
        return name;
    }

    @Test
    void listIncludesAnAppliedExport() {
        String name = applyExport("export-list-" + System.nanoTime());
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.scope", equalTo("sales"));
    }

    @Test
    void getReturnsTheStoredYamlForAKnownName() {
        String name = applyExport("export-get-" + System.nanoTime());
        given().headers(TestIdentity.tenantAndUser(1))
            .accept("application/yaml")
            .get("/api/exports/" + name)
            .then().statusCode(200)
            .body(containsString("kind: Export"));
    }

    @Test
    void getReturns404ForAnUnknownName() {
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/no-such-export-676")
            .then().statusCode(404);
    }

    /**
     * ExportApplyResource normalizes metadata.name (strip + lowercase) before storing — matching
     * View/App's own precedent — so a caller who types the manifest's ORIGINAL casing (or just
     * assumes case doesn't matter, the way every other lookup in this app behaves) must still
     * resolve the row. The store itself does the normalizing, not this door, so the fix is
     * structural rather than a per-caller reminder to lowercase first.
     */
    @Test
    void getResolvesRegardlessOfCallerCasing() {
        String mixedCase = "Export-Case-Sensitivity-" + System.nanoTime();
        applyExport(mixedCase);

        given().headers(TestIdentity.tenantAndUser(1))
            .accept("application/yaml")
            .get("/api/exports/" + mixedCase)
            .then().statusCode(200)
            .body(containsString("kind: Export"));
    }
}
