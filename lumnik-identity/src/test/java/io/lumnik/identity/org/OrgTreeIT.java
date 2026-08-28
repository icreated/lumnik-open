package io.lumnik.identity.org;

import io.lumnik.identity.geo.Geocoder;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class OrgTreeIT {

    @InjectMock Geocoder geocoder;

    @Inject TenantRepository tenants;
    @Inject OrgRepository orgs;

    private Long tenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Mockito.when(geocoder.resolve(Mockito.any())).thenReturn(Optional.empty());
        Tenant t = new Tenant("TREE-TEST", "Tree Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        orgs.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'TREE-TEST'");
    }

    @Test
    void tree_depth_param() {
        Long root = createOrg(null, "TREE-ROOT", "Root");
        Long child = createOrg(root, "TREE-CH", "Child");
        createOrg(child, "TREE-GC", "Grandchild");

        given().header("X-Tenant-ID", tenantId.toString())
            .when().get("/api/tenants/" + tenantId + "/orgs/tree?root=" + root + "&depth=1")
            .then().statusCode(200)
            .body("[0].children", hasSize(1))
            .body("[0].children[0].children", hasSize(0));

        given().header("X-Tenant-ID", tenantId.toString())
            .when().get("/api/tenants/" + tenantId + "/orgs/tree?root=" + root + "&depth=2")
            .then().statusCode(200)
            .body("[0].children[0].children", hasSize(1));
    }

    @Test
    void reparentingUnderOwnDescendantIsRejectedAsCycle() {
        Long a = createOrg(null, "CYC-A", "A");
        Long b = createOrg(a, "CYC-B", "B");
        Long c = createOrg(b, "CYC-C", "C");

        // A->B->C is a valid 3-level tree.
        given().header("X-Tenant-ID", tenantId.toString())
            .when().get("/api/tenants/" + tenantId + "/orgs/tree?root=" + a + "&depth=2")
            .then().statusCode(200)
            .body("[0].children[0].children", hasSize(1));

        // Re-parenting A under its own descendant C must be rejected — do NOT call the
        // tree endpoint afterwards: pre-fix, buildNode would recurse into the cycle forever.
        given().contentType("application/json")
            .header("X-Tenant-ID", tenantId.toString())
            .body("{\"name\":\"A\",\"parentOrgId\":" + c + ",\"summary\":false}")
            .when().put("/api/tenants/" + tenantId + "/orgs/" + a)
            .then().statusCode(400)
            .body(containsString("Organization cycle"));
    }

    private Long createOrg(Long parent, String code, String name) {
        String body = "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"summary\":false"
            + (parent == null ? "" : ",\"parentOrgId\":" + parent) + "}";
        return given().contentType("application/json")
            .header("X-Tenant-ID", tenantId.toString())
            .body(body)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(201).extract().jsonPath().getLong("id");
    }
}
