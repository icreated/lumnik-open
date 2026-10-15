package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What this proves, and how: {@code aConfiguredTimeoutOverrideIsInjectedNotHardcoded} asserts —
 * against the same package-private {@code EntitySnapshotDownloadResource.timeoutSeconds} field
 * {@code download()} passes straight to {@code .timeout(...)} — that the injected value under
 * this profile's {@code lumnik.export.snapshot.timeout-seconds=2} override is genuinely {@code 2},
 * not the {@code 1800} default. That rules out "the property is declared but never read" and "the
 * value is hardcoded" — a fast download alone (the other test here) cannot rule out either, since
 * it would return 200 exactly the same way whether the override were honored, ignored, or
 * hardcoded.
 *
 * <p>What neither test proves: that {@code QuarkusTransaction...timeout(timeoutSeconds)} — a
 * direct call on that same field, confirmed by reading {@code download()} — actually aborts a
 * transaction that outlives it. A throwaway probe (not committed — raw JDBC,
 * {@code QuarkusTransaction.requiringNew().timeout(1)} wrapping a raw {@code SELECT pg_sleep(3)})
 * confirmed the mechanism genuinely aborts: ~3s elapsed, then {@code QuarkusTransactionException:
 * RollbackException: ARJUNA016102: The transaction is not active}. But {@code pg_sleep} isn't
 * representable inside the entity source table the fixture's plain-column DDL creates, and seeding
 * enough rows to make the real pipeline itself outlast a short timeout is a timing-based test —
 * exactly the kind of noise-prone assertion this codebase avoids elsewhere. Proving the abort path
 * through the door itself would need a small {@code EntitySnapshotStreamer} test seam (a test-only
 * overload accepting a raw SQL suffix, or similar) — a production-code change judged out of scope
 * for this task. See task-6-report.md for the full investigation.
 */
@QuarkusTest
@TestProfile(EntitySnapshotDownloadTimeoutIT.Profile.class)
class EntitySnapshotDownloadTimeoutIT {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("lumnik.export.snapshot.timeout-seconds", "2");
        }
    }

    private static final String ENTITY = """
        kind: Entity
        name: SnapshotTimeoutEntity677
        match: [code]
        scopes: [sales]
        sources:
          - table: t_snapshottimeout677_erp
            map:
              cust_id: code
        """;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntitySnapshotDownloadResource resource;

    @BeforeEach
    void applyEntityAndSeedARow() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_snapshottimeout677_erp",
            "v_snapshottimeoutentity677", "cust_id");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_snapshottimeout677_erp (tenant_id, cust_id) VALUES (1, '1')");
        }
    }

    @Test
    void aConfiguredTimeoutOverrideIsInjectedNotHardcoded() {
        // download() passes this exact field to QuarkusTransaction...timeout(timeoutSeconds) —
        // see EntitySnapshotDownloadResource. Under Profile's 2s override, a hardcoded 1800 or a
        // silently-dropped @ConfigProperty would both leave this at 1800; only genuine injection
        // of the override produces 2.
        assertEquals(2, resource.timeoutSeconds);
    }

    @Test
    void aTightConfiguredTimeoutDoesNotBreakANormalDownload() throws Exception {
        // See the class javadoc: this proves the 2s override is wired through to
        // requiringNew().timeout(...) without breaking a normal fast download — it does NOT
        // exercise the abort path (no pg_sleep here; that would need a production test seam,
        // deliberately left out of this task's scope).
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/entities/SnapshotTimeoutEntity677/download")
            .then().statusCode(200)
            .body(equalTo("\"code\"\r\n\"1\"\r\n"));
    }
}
