package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.ExportManifestFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Proves the thing that actually matters: tenant A's existing row cannot block or leak into
 * tenant B's CREATION of the same export name. Seeding the name under both tenants up front would
 * only exercise the ordinary upsert path (B updating its own already-existing row) — the sequence
 * below seeds tenant A alone, then applies under tenant B, so B's apply must genuinely create a
 * new, independent row for the assertions to be meaningful.
 *
 * <p>A plain {@code @QuarkusTest}'s datasource can own the table under a BYPASSRLS-equivalent
 * role, which would hide a missing-{@code @Transactional} regression here (RLS's
 * {@code SET LOCAL app.current_tenant} guard silently going empty) — this store's explicit
 * {@code tenant_id} filtering alone doesn't catch that class of bug, only
 * {@code RlsEnforcedProfile} does.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ExportManifestTenantIsolationIT {

    private static final String SHARED_NAME = "shared-name-676";
    private static final String TENANT_A_YAML =
        "apiVersion: apps.lumnik.io/v1\nkind: Export\n# tenant-A-original\n";

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /** Seeds tenant A's row directly, bypassing RLS on purpose — the setup, not the thing under test. */
    private void seedTenantARow() throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector.export_manifest "
               + "(tenant_id, name, scope, entity_name, destination_kind, yaml_text) "
               + "VALUES (1, ?, 'sales', 'SharedEntity676', 'sftp', ?) "
               + "ON CONFLICT (tenant_id, name) DO NOTHING")) {
            ps.setString(1, SHARED_NAME);
            ps.setString(2, TENANT_A_YAML);
            ps.executeUpdate();
        }
    }

    private static final String TENANT_B_MANIFEST = ExportManifestFixture.sftpManifest(
        SHARED_NAME, "sales", "SharedEntityTenantB676", "code");

    private static final String TENANT_B_ENTITY = """
        kind: Entity
        name: SharedEntityTenantB676
        match: [code]
        scopes: [sales]
        sources:
          - table: t_sharedentity_tb_erp
            map:
              cust_id: code
        """;

    /** Same physical-table requirement Task 4's own execution surfaced: the declared source
     *  table must exist before /api/entities/apply can compile its view, or it refuses with 409. */
    private void seedTenantBSourceTable() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_sharedentity_tb_erp",
            "v_sharedentitytenantb676", "cust_id");
    }

    @Test
    void tenantBsApplyCreatesItsOwnRowWithoutTouchingTenantAs() throws Exception {
        seedTenantARow();

        // 1) tenant A's row exists before B ever acts.
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/" + SHARED_NAME)
            .then().statusCode(200)
            .body(containsString("tenant-A-original"));

        // 2) apply the same name under tenant B, through the real door.
        seedTenantBSourceTable();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(2))
            .contentType("application/yaml").body(TENANT_B_ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);

        // 3) B's apply must succeed and create B's OWN row — not a no-op, not an error.
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(2))
            .contentType("application/yaml").body(TENANT_B_MANIFEST)
            .post("/api/exports/apply")
            .then().statusCode(200)
            .body("name", equalTo(SHARED_NAME));

        // 4) tenant A's row is byte-for-byte unchanged.
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/" + SHARED_NAME)
            .then().statusCode(200)
            .body(equalTo(TENANT_A_YAML));

        // 5) each tenant sees exactly one row for the shared name, never both, never the other's.
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports")
            .then().statusCode(200)
            .body("findAll { it.name == '" + SHARED_NAME + "' }", hasSize(1))
            .body("find { it.name == '" + SHARED_NAME + "' }.entityName", equalTo("SharedEntity676"));

        given().headers(TestIdentity.tenantAndUser(2))
            .get("/api/exports")
            .then().statusCode(200)
            .body("findAll { it.name == '" + SHARED_NAME + "' }", hasSize(1))
            .body("find { it.name == '" + SHARED_NAME + "' }.entityName",
                equalTo("SharedEntityTenantB676"));
    }
}
