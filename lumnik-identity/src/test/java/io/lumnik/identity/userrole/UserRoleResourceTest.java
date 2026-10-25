package io.lumnik.identity.userrole;

import io.lumnik.identity.role.Role;
import io.lumnik.identity.role.RoleRepository;
import io.lumnik.identity.role.UserLevel;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
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
class UserRoleResourceTest {

    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject RoleRepository roles;
    @Inject UserRoleRepository userRoles;

    private Long tenantId;
    private Long userId;
    private Long roleId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("URTEST", "UserRole Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();

        User u = new User(tenantId, "urtest.user", "UR Test User");
        users.persist(u);
        userId = u.getId();

        Role r = new Role(tenantId, "UR Test Role", UserLevel.CLIENT_ORG);
        roles.persist(r);
        roleId = r.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        userRoles.delete("tenantId = ?1", tenantId);
        users.delete("tenantId = ?1", tenantId);
        roles.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'URTEST'");
    }

    @Test
    void assignAndListRole() {
        given().when().post("/api/tenants/" + tenantId + "/users/" + userId + "/roles/" + roleId)
            .then().statusCode(201)
            .body("userId", equalTo(userId.intValue()))
            .body("roleId", equalTo(roleId.intValue()))
            .body("active", equalTo(true));

        given().when().get("/api/tenants/" + tenantId + "/users/" + userId + "/roles")
            .then().statusCode(200)
            .body("roleId", hasItem(roleId.intValue()));
    }

    @Test
    void duplicateAssignmentIsRejected() {
        given().when().post("/api/tenants/" + tenantId + "/users/" + userId + "/roles/" + roleId)
            .then().statusCode(201);

        given().when().post("/api/tenants/" + tenantId + "/users/" + userId + "/roles/" + roleId)
            .then().statusCode(409)
            .contentType(ContentType.JSON)
            .body("error", equalTo("role " + roleId + " is already assigned to user " + userId));
    }

    @Test
    void removeRoleAssignment() {
        given().when().post("/api/tenants/" + tenantId + "/users/" + userId + "/roles/" + roleId)
            .then().statusCode(201);

        given().when().delete("/api/tenants/" + tenantId + "/users/" + userId + "/roles/" + roleId)
            .then().statusCode(204);

        given().when().get("/api/tenants/" + tenantId + "/users/" + userId + "/roles")
            .then().statusCode(200)
            .body("roleId", not(hasItem(roleId.intValue())));
    }

    @Test
    void assignRoleToUnknownUserReturns404() {
        given().when().post("/api/tenants/" + tenantId + "/users/999999/roles/" + roleId)
            .then().statusCode(404);
    }

    @Test
    void assignUnknownRoleReturns404() {
        given().when().post("/api/tenants/" + tenantId + "/users/" + userId + "/roles/999999")
            .then().statusCode(404);
    }

    @Test
    void crossTenantUserAccessReturns404() {
        given().when().get("/api/tenants/999999/users/" + userId + "/roles")
            .then().statusCode(404);
    }

    @Test
    void crossTenantRoleAssignmentIsRejected() {
        given().when().post("/api/tenants/999999/users/" + userId + "/roles/" + roleId)
            .then().statusCode(404);
    }
}
