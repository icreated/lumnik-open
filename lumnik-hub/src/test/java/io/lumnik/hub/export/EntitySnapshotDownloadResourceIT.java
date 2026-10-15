package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class EntitySnapshotDownloadResourceIT {

    private static final String ENTITY = """
        kind: Entity
        name: SnapshotDownloadEntity677
        match: [code]
        scopes: [sales]
        sources:
          - table: t_snapshotdl677_erp
            map:
              cust_id: code
              cust_name: name
        """;

    private static String integratorSales() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:sales"), null);
    }

    /** {@code EntityApplyResource} ({@code POST /api/entities/apply}) is {@code lm_admin}-gated,
     *  not {@code lm_integrator} — used ONLY by the fixture setup below, never by the download
     *  assertions, so the door under test still proves its own {@code lm_integrator} gate. */
    private static String adminSales() {
        return NoBypassOidcProfile.token(Set.of("lm_admin", "scope:sales"), null);
    }

    private static String integratorMagasin() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:magasin"), null);
    }

    private static String userOnly() {
        return NoBypassOidcProfile.token(Set.of("lm_user"), null);
    }

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** `NoBypassOidcProfile` sets `lumnik.security.dev-bypass=false` for this whole test class's
     *  Quarkus application instance (a `@TestProfile` config override applies once at boot, not
     *  per request) — so every call in this class, including this fixture setup, must authenticate
     *  with a real bearer JWT. `/api/entities/apply` (`EntityApplyResource`) is `lm_admin`-gated
     *  (not `lm_integrator` — verified against the source), so fixture setup uses a dedicated
     *  `adminSales()` token; the download door under test is exercised exclusively with the
     *  `lm_integrator` tokens below. */
    @BeforeEach
    void applyEntityAndSeedRows() throws Exception {
        // core.audit_outbox is NOT reset between test methods in this class (unlike the fixture
        // source table, which EntitySourceTableFixture.create drops/recreates each time) — every
        // method shares the same entity name, so a prior method's audit row would otherwise leak
        // into a later method's "0 rows" assertion depending on JUnit's method execution order.
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM core.audit_outbox WHERE entity_key = 'SnapshotDownloadEntity677'");
        }
        EntitySourceTableFixture.create(adminDs, "t_snapshotdl677_erp",
            "v_snapshotdownloadentity677", "cust_id", "cust_name");
        given().config(YamlBody.CONFIG)
            .header("Authorization", "Bearer " + adminSales())
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_snapshotdl677_erp (tenant_id, cust_id, cust_name) "
                + "VALUES (1, '1', 'Ann'), (1, '2', 'Bob')");
        }
    }

    private long auditRowCountFor(String key) throws Exception {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT count(*) FROM core.audit_outbox WHERE entity_key = '" + key
               + "' AND operation = 'EGRESS'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** {@code row_count}/{@code byte_count}/{@code sha256} the download's audit row actually
     *  carries — the design's stated "durable home" for these values, since they're never in the
     *  HTTP response. Assumes exactly one EGRESS row for {@code key} (callers assert the count first). */
    private record AuditChanges(String rowCount, String byteCount, String sha256) {}

    private AuditChanges auditChangesFor(String key) throws Exception {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT changes ->> 'row_count', changes ->> 'byte_count', changes ->> 'sha256' "
               + "FROM core.audit_outbox WHERE entity_key = '" + key + "' AND operation = 'EGRESS'")) {
            rs.next();
            return new AuditChanges(rs.getString(1), rs.getString(2), rs.getString(3));
        }
    }

    @Test
    void successfulDownloadReturnsCsvWithHeadersAndAudits() throws Exception {
        given().header("Authorization", "Bearer " + integratorSales())
            .get("/api/exports/entities/SnapshotDownloadEntity677/download")
            .then().statusCode(200)
            .header("Content-Type", containsString("text/csv"))
            .header("Content-Type", containsString("charset=UTF-8"))
            .header("Content-Disposition", containsString("attachment"))
            .header("Content-Disposition", containsString("SnapshotDownloadEntity677.csv"))
            .body(equalTo("\"code\",\"name\"\r\n\"1\",\"Ann\"\r\n\"2\",\"Bob\"\r\n"));

        assertEquals(1, auditRowCountFor("SnapshotDownloadEntity677"));

        // The audit record is the durable home for row_count/byte_count/sha256 (never in the HTTP
        // response — see the design doc) — computed for the known body above via:
        // python3 -c "import hashlib; b='\"code\",\"name\"\r\n\"1\",\"Ann\"\r\n\"2\",\"Bob\"\r\n'.encode('utf-8'); print(len(b), hashlib.sha256(b).hexdigest())"
        AuditChanges changes = auditChangesFor("SnapshotDownloadEntity677");
        assertEquals("2", changes.rowCount());
        assertEquals("37", changes.byteCount());
        assertEquals("bf11dbb8e1c71e43043c582a569bef2377d7521c4a90b1485de83f4abd42b65d", changes.sha256());
    }

    /**
     * 401 or 403 depending on which layer refuses first (Quarkus OIDC's own policy, or
     * PermissionInterceptor finding an empty role set) — same ambiguity RoleResourceNoBypassIT's
     * getRoles_anonymous_returns401or403() already accepts for an unauthenticated no-bypass
     * request; match that precedent rather than asserting a single value that may not hold.
     */
    @Test
    void unauthenticatedRequestIsRefusedWithNoCsvBodyAndNoAudit() throws Exception {
        given()
            .get("/api/exports/entities/SnapshotDownloadEntity677/download")
            .then().statusCode(anyOf(equalTo(401), equalTo(403)));

        assertEquals(0, auditRowCountFor("SnapshotDownloadEntity677"));
    }

    @Test
    void unknownEntityIsRefusedWithNoCsvBodyAndNoAudit() throws Exception {
        given().header("Authorization", "Bearer " + integratorSales())
            .get("/api/exports/entities/NoSuchEntity677/download")
            .then().statusCode(404);

        assertEquals(0, auditRowCountFor("NoSuchEntity677"));
    }

    /** A caller bound to a DIFFERENT scope than the one the entity declares (`sales`) — the
     *  ScopeRefusalOnTheWireIT shape, applied to this door. */
    @Test
    void callerBoundToAnotherScopeIsRefusedWithNoCsvBodyAndNoAudit() throws Exception {
        given().header("Authorization", "Bearer " + integratorMagasin())
            .get("/api/exports/entities/SnapshotDownloadEntity677/download")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        assertEquals(0, auditRowCountFor("SnapshotDownloadEntity677"));
    }

    /** Authenticated, but without lm_integrator — proves the door is actually gated by role, not
     *  merely documented as such (PermissionInterceptor is a no-op under dev-bypass, so this must
     *  run under NoBypassOidcProfile to mean anything). */
    @Test
    void authenticatedWithoutIntegratorRoleIsRefusedWithNoCsvBodyAndNoAudit() throws Exception {
        given().header("Authorization", "Bearer " + userOnly())
            .get("/api/exports/entities/SnapshotDownloadEntity677/download")
            .then().statusCode(403);

        assertEquals(0, auditRowCountFor("SnapshotDownloadEntity677"));
    }
}
