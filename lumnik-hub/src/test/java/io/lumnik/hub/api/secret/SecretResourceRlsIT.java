package io.lumnik.hub.api.secret;

import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;

/**
 * The secret store, read under the posture it runs in.
 *
 * <p>{@link SecretResourceIT} already walks create → list → describe → delete and passes. It is a
 * plain {@code @QuarkusTest}, so its default datasource owns its tables and row-level security
 * never bites — the flow works there in a world where the policy does not apply. On a real hub it
 * did not: {@code lm secret set} answered "Created", and {@code lm secret list} answered
 * "(no secrets)" about the row it had just written.
 *
 * <p>The cause is one annotation. {@code TenantConnectionInterceptor} binds the tenant with
 * {@code SET LOCAL app.current_tenant}, and {@code SET LOCAL} is transaction-scoped: outside a
 * transaction it is discarded before the query runs. The three writes carry
 * {@code @Transactional}; the two reads did not, so their GUC was empty, the policy's
 * {@code NULLIF(…, '')::bigint} became NULL, and {@code tenant_id = NULL} matched nothing.
 *
 * <p>RLS failed closed, which is the good half — no other tenant's secret was ever at risk. The
 * bad half is that failing closed here is indistinguishable from an empty store, so the store
 * looked empty rather than broken.
 *
 * <p>This class is the twin that CAN see it: same walk, same header, on the enforced harness.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class SecretResourceRlsIT {

    @Test
    void a_secret_that_was_written_can_be_listed_and_described() {
        String name = "RLS_IT_KEY_" + System.nanoTime();

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"super-secret\",\"description\":\"rls it\"}")
            .post("/api/platform/secrets")
            .then().statusCode(201);

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .get("/api/platform/secrets")
            .then().statusCode(200)
            .body(containsString(name));   // the row the write just made, seen by the read

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(200)
            .body(containsString(name));
    }

    /**
     * The blast radius, pinned rather than assumed. I wrote that the blind read must also have
     * disarmed {@code create}'s anti-duplicate check, since it decides on the same
     * {@code findByName} — and this row said no while the first one was still red: {@code create}
     * carries {@code @Transactional}, so ITS lookup always saw the row. The defect was confined to
     * the two reads, and the difference between a display bug and a data bug was one annotation
     * that happened to be there.
     */
    @Test
    void a_duplicate_name_is_still_refused_which_needs_the_lookup_to_see() {
        String name = "RLS_IT_DUP_" + System.nanoTime();
        String body = "{\"name\":\"" + name + "\",\"value\":\"v\",\"description\":\"d\"}";

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body(body).post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body(body).post("/api/platform/secrets").then().statusCode(409);
    }
}
