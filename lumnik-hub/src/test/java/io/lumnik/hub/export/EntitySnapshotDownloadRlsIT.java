package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Proves the download reads through RLS enforcement, not merely through code that happens to
 * filter by tenant — same reasoning as {@code ExportManifestTenantIsolationIT}: a plain
 * {@code @QuarkusTest} datasource can own the table under a BYPASSRLS-equivalent role, which
 * would hide a missing-tenant-binding regression here.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntitySnapshotDownloadRlsIT {

    private static final String ENTITY = """
        kind: Entity
        name: SnapshotRlsEntity677
        match: [code]
        scopes: [sales]
        sources:
          - table: t_snapshotrls677_erp
            map:
              cust_id: code
              cust_name: name
        """;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeEach
    void applyEntityAndSeedBothTenants() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_snapshotrls677_erp", "v_snapshotrlsentity677",
            "cust_id", "cust_name");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_snapshotrls677_erp (tenant_id, cust_id, cust_name) "
                + "VALUES (1, 'a1', 'TenantOneRow'), (2, 'b1', 'TenantTwoRow')");
        }
    }

    @Test
    void tenantOneNeverSeesTenantTwosRow() {
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/entities/SnapshotRlsEntity677/download")
            .then().statusCode(200)
            .body(equalTo("\"code\",\"name\"\r\n\"a1\",\"TenantOneRow\"\r\n"))
            .body(not(containsString("TenantTwoRow")));
    }

    @Test
    void tenantTwoNeverSeesTenantOnesRow() {
        given().headers(TestIdentity.tenantAndUser(2))
            .get("/api/exports/entities/SnapshotRlsEntity677/download")
            .then().statusCode(200)
            .body(equalTo("\"code\",\"name\"\r\n\"b1\",\"TenantTwoRow\"\r\n"))
            .body(not(containsString("TenantOneRow")));
    }
}
