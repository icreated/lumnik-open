package io.lumnik.hub.webhook;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Another tenant's integration is a 404 by id — in the PLAIN harness, on purpose.
 *
 * <p>This is a bare {@code @QuarkusTest}: the connecting user owns the schema, so RLS never bites,
 * and the two doors under test — {@code update} and {@code delete} — are {@code @Transactional},
 * so Hibernate's {@code tenantFilter} is OFF on their session (it is enabled per request, outside
 * any transaction). With RLS gone and the filter gone, the ONLY thing that turns a foreign id into
 * a 404 is the {@code Require.owned} predicate in the query. Seen RED against the old
 * {@code findById} shape: tenant 6102 renamed and deleted tenant 6101's integration through the
 * front door, 200 and 204 where 404 is asserted.
 *
 * <p>The other id-from-request doors are converted too, but not asserted here — they cannot go red
 * in this harness, so pinning them would prove nothing:
 * <ul>
 *   <li>{@code test} is NOT {@code @Transactional}, so the request-session {@code tenantFilter}
 *       already scopes its {@code findLiveForTenant} query — verified: neutralising the predicate
 *       still returns 404. The predicate there is robustness against a future
 *       {@code @Transactional}, not a live fix.</li>
 *   <li>{@code replay} through an integration is doubly scoped — endpoint AND delivery, both by
 *       tenant — so no single predicate can be shown to carry it.</li>
 * </ul>
 * A test that only passed with RLS on would prove the database; these two prove the code.
 */
@QuarkusTest
class IntegrationTenantPredicateIT {

    private static final long OWNER = 6101L;
    private static final long OTHER = 6102L;

    /** Create as the owner, returning the id straight off the 201 body. Name is unique so the
     *  two tests do not collide on (tenant_id, name). */
    private long createAsOwner() {
        return ((Number) given().headers(TestIdentity.tenantAndUser(OWNER)).contentType("application/json")
            .body("{\"name\":\"predicate-it-" + System.nanoTime() + "\","
                + "\"targetUrl\":\"http://localhost:9/w\",\"secret\":\"s\",\"eventTypes\":[\"org.created\"]}")
            .post("/api/tenants/" + OWNER + "/integrations")
            .then().statusCode(201).extract().path("id")).longValue();
    }

    @Test
    void another_tenant_cannot_update_it_by_id() {
        long id = createAsOwner();
        given().headers(TestIdentity.tenantAndUser(OTHER)).contentType("application/json")
            .body("{\"name\":\"hijacked\"}")
            .put("/api/tenants/" + OTHER + "/integrations/" + id)
            .then().statusCode(404);
        // and the owner's row did not take the rename
        given().headers(TestIdentity.tenantAndUser(OWNER))
            .get("/api/tenants/" + OWNER + "/integrations/" + id)
            .then().statusCode(200).body("name", not(equalTo("hijacked")));
    }

    @Test
    void another_tenant_cannot_delete_it_by_id() {
        long id = createAsOwner();
        given().headers(TestIdentity.tenantAndUser(OTHER))
            .delete("/api/tenants/" + OTHER + "/integrations/" + id)
            .then().statusCode(404);
        given().headers(TestIdentity.tenantAndUser(OWNER))
            .get("/api/tenants/" + OWNER + "/integrations/" + id)
            .then().statusCode(200);
    }
}
