package io.lumnik.hub.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The rate boundary through the real HTTP stack: 3rd call in the window → 429 with
 * Retry-After; another tenant is untouched. view-generate rpm=2 comes from the profile;
 * the global %test default stays 0 so no other IT trips the limiter.
 *
 * <p>Exercised against {@code /api/views/generate} — an OPEN @RateLimited endpoint — so the
 * boundary is provable in both editions. The original target, the rag chat door, has no
 * resource in the open edition and an UNMATCHED path is never rate-limited (resourceInfo is
 * null before matching) — the Act 2 dry-run caught the filter silently out of the loop there.
 */
@QuarkusTest
@TestProfile(RateLimitFilterIT.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitFilterIT {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("lumnik.ratelimit.view-generate.rpm", "2");
        }
    }

    @org.junit.jupiter.api.BeforeAll
    static void avoidWindowEdge() throws InterruptedException {
        // The limiter's window is a fixed epoch minute; starting the 3-call sequence in
        // its dying seconds could roll the counter mid-test and flake the 429 assertion.
        long intoWindow = java.time.Instant.now().getEpochSecond() % 60;
        if (intoWindow >= 55) Thread.sleep((60 - intoWindow) * 1000 + 200);
    }

    private static io.restassured.response.Response chat(String tenant) {
        return given()
            .header("X-Tenant-ID", tenant)
            .header("Content-Type", "application/json")
            .body("{\"scope\":\"stock\",\"description\":\"liste des articles\"}")
            .post("/api/views/generate");
    }

    @Test
    @Order(1)
    void third_call_in_window_is_429_with_retry_after() {
        // The first two calls pass the filter (whatever the resource then answers —
        // 200/503 — is irrelevant here: the boundary under test is BEFORE the resource).
        Assertions.assertNotEquals(429, chat("1000").statusCode());
        Assertions.assertNotEquals(429, chat("1000").statusCode());
        chat("1000").then()
            .statusCode(429)
            .header("Retry-After", matchesPattern("\\d+"))
            .body("error", equalTo("rate limit exceeded"));
    }

    @Test
    @Order(2)
    void another_tenant_has_its_own_window() {
        Assertions.assertNotEquals(429, chat("2000").statusCode());
    }

    @jakarta.inject.Inject
    io.micrometer.core.instrument.MeterRegistry registry;

    @Test
    @Order(3)
    void a_429_is_visible_server_side_as_a_metric() {
        // Audit P3: a 429 storm was completely silent server-side (debug-level log, no
        // counter) — "attack or busy Monday?" had no observable answer. The window from
        // Order(1) is still saturated for tenant 1000: this call must be limited AND counted.
        chat("1000").then().statusCode(429);
        double count = registry.counter("lumnik_rate_limited_total", "bucket", "view-generate").count();
        Assertions.assertTrue(count >= 1.0,
            "a 429 must increment lumnik_rate_limited_total{bucket} — got " + count);
    }
}
