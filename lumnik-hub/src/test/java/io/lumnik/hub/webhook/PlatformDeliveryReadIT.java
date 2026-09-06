package io.lumnik.hub.webhook;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** The flattened deliveries read: every integration at once, failures first, tenant-bound. */
@QuarkusTest
class PlatformDeliveryReadIT {

    @Inject EntityManager em;

    @BeforeEach
    void seed() {
        seedProbes();
    }

    @Transactional
    void seedProbes() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'outputs.probe%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'outputs-probe%'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'outputs-probe-a', 'http://localhost:1/a', 'generic', 'NONE', '{outputs.probe.*}', TRUE)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (2, 'outputs-probe-t2', 'http://localhost:1/t2', 'generic', 'NONE', '{outputs.probe.*}', TRUE)
            """).executeUpdate();
        // A second tenant-1 integration — the flattening must cross integrations, not just rows
        // within one, or "what did not arrive?" still requires knowing which endpoint to look at.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'outputs-probe-b', 'http://localhost:1/b', 'generic', 'NONE', '{outputs.probe.*}', TRUE)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, http_status, error, duration_ms, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'outputs.probe.b-ko', '{}'::jsonb, 502, 'connect timed out', 2001,
                   NOW() - INTERVAL '2 hours', 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'outputs-probe-b'
            """).executeUpdate();
        // A recent SUCCESS and an OLDER FAILURE for tenant 1 — the failure must come first
        // despite being older, because failures-first is the server's promise.
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, http_status, error, duration_ms, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'outputs.probe.ok', '{}'::jsonb, 200, NULL, 12, NOW(), 1, TRUE
            FROM core.webhook_endpoint e WHERE e.name = 'outputs-probe-a'
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, http_status, error, duration_ms, attempted_at, attempts, succeeded)
            SELECT 1, e.id, 'outputs.probe.ko', '{}'::jsonb, 502, 'connect timed out', 3001,
                   NOW() - INTERVAL '1 hour', 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'outputs-probe-a'
            """).executeUpdate();
        // Another tenant's failure must never appear in tenant 1's answer.
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, http_status, error, duration_ms, attempted_at, attempts, succeeded)
            SELECT 2, e.id, 'outputs.probe.t2', '{}'::jsonb, 500, 'nope', 5, NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = 'outputs-probe-t2'
            """).executeUpdate();
    }

    @Test
    void deliveries_are_flattened_failures_first_and_tenant_bound() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/deliveries")
            .then().statusCode(200)
            .body("findAll { it.eventType.startsWith('outputs.probe') }", hasSize(3))
            .body("findAll { it.eventType.startsWith('outputs.probe') }[0].eventType", equalTo("outputs.probe.ko"))
            .body("findAll { it.eventType.startsWith('outputs.probe') }[0].succeeded", equalTo(false))
            .body("findAll { it.eventType.startsWith('outputs.probe') }[0].integration", equalTo("outputs-probe-a"))
            .body("findAll { it.eventType == 'outputs.probe.t2' }", hasSize(0))
            .body("findAll { it.integration == 'outputs-probe-a' }", hasSize(2))
            .body("findAll { it.integration == 'outputs-probe-b' }", hasSize(1))
            .body("findAll { it.eventType == 'outputs.probe.b-ko' }[0].integration", equalTo("outputs-probe-b"));
    }

    @Test
    void limit_is_honored() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/deliveries?limit=1")
            .then().statusCode(200)
            .body("size()", equalTo(1));
    }
}
