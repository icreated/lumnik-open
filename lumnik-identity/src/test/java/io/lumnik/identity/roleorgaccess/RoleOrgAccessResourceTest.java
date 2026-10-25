package io.lumnik.identity.roleorgaccess;

import io.lumnik.identity.org.OrgRepository;
import io.lumnik.identity.org.Organization;
import io.lumnik.identity.role.Role;
import io.lumnik.identity.role.RoleRepository;
import io.lumnik.identity.role.UserLevel;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RoleOrgAccessResourceTest {

    @Inject TenantRepository tenants;
    @Inject RoleRepository roles;
    @Inject OrgRepository orgs;
    @Inject RoleOrgAccessRepository grants;

    private Long tenantId;
    private Long roleId;
    private Long orgId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("ROATEST", "RoleOrgAccess Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();

        Role r = new Role(tenantId, "ROA Test Role", UserLevel.CLIENT_ORG);
        r.setManual(true);
        roles.persist(r);
        roleId = r.getId();

        Organization o = new Organization(tenantId, "ROA-HQ", "ROA Headquarters");
        orgs.persist(o);
        orgId = o.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        grants.delete("tenantId = ?1", tenantId);
        orgs.delete("tenantId = ?1", tenantId);
        roles.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'ROATEST'");
    }

    @Test
    void grantAndListOrgAccess() {
        given().when().post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(201)
            .body("roleId", equalTo(roleId.intValue()))
            .body("orgId", equalTo(orgId.intValue()))
            .body("readOnly", equalTo(false))
            .body("active", equalTo(true));

        given().when().get("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs")
            .then().statusCode(200)
            .body("orgId", hasItem(orgId.intValue()));
    }

    @Test
    void grantReadOnlyAccess() {
        given().when()
            .post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId + "?readOnly=true")
            .then().statusCode(201)
            .body("readOnly", equalTo(true));
    }

    @Test
    void duplicateGrantIsRejected() {
        given().when().post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(201);

        given().when().post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(409)
            .contentType(ContentType.JSON)
            .body("error", equalTo("access to org " + orgId + " is already granted to role " + roleId));
    }

    @Test
    void revokeOrgAccess() {
        given().when().post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(201);

        given().when().delete("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(204);

        given().when().get("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs")
            .then().statusCode(200)
            .body("orgId", not(hasItem(orgId.intValue())));
    }

    @Test
    void unknownRoleReturns404() {
        given().when().post("/api/tenants/" + tenantId + "/roles/999999/orgs/" + orgId)
            .then().statusCode(404);
    }

    @Test
    void unknownOrgReturns404() {
        given().when().post("/api/tenants/" + tenantId + "/roles/" + roleId + "/orgs/999999")
            .then().statusCode(404);
    }

    @Test
    void crossTenantRoleAccessReturns404() {
        given().when().get("/api/tenants/999999/roles/" + roleId + "/orgs")
            .then().statusCode(404);
    }

    @Test
    void crossTenantGrantIsRejected() {
        given().when().post("/api/tenants/999999/roles/" + roleId + "/orgs/" + orgId)
            .then().statusCode(404);
    }
}
