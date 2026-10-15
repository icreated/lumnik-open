package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 follow-up (#671, design doc §1): proves the re-mark's {@code ON CONFLICT DO UPDATE} works
 * under the REAL privilege chain, not just the DevServices superuser-equivalent {@code @QuarkusTest}
 * default. {@link RlsEnforcedProfile} is the only harness where the default datasource genuinely
 * connects as non-owner {@code lumnik_app} (mirroring {@code infra/postgres/init/01-roles.sh}), so
 * V56's {@code REVOKE UPDATE ON core.search_projection_mark FROM lumnik_app} and V57's narrower
 * {@code GRANT UPDATE (id, marked_at)} both actually apply here — under the plain shared DevServices
 * container every role is superuser-equivalent and this exact bug would pass silently.
 *
 * <p>Applying the SAME SearchIndex twice hits {@code SearchIndexStore.upsert}'s {@code
 * mark.markAll(conn, ...)} on the request-scoped (lumnik_app) connection both times: the first apply
 * INSERTs the {@code ALL} mark with no conflict, the second re-marks the same undrained row — the
 * exact statement the Critical review finding was about. A 500 here (permission denied) would mean
 * the grant regressed; a strictly-higher {@code id} on the second call proves the fix is live under
 * real, non-superuser privileges.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class SearchProjectionMarkReMarkRlsIT {

    private static final String NAME = "remark-privilege-671";
    private static final String ENTITY = "RemarkPrivilege671";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    @Test
    void reApplyingTheSameSearchIndexReMarksUnderTheRealLumnikAppRole() throws Exception {
        yaml(1).body(entity(adminDs, ENTITY, "t_remarkprivilege671_erp", List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        // First apply: a fresh INSERT into core.search_projection_mark, no conflict yet.
        yaml(1).body(searchIndex(NAME, ENTITY, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        long firstId = allMarkId(1, NAME, "index-apply");

        // Second apply of the IDENTICAL manifest: SearchIndexStore.upsert's ON CONFLICT DO UPDATE
        // fires again, so mark.markAll re-marks the same (tenant, search_index, ALL, '') row on the
        // REQUEST-scoped (lumnik_app, non-superuser under this profile) connection. A permission
        // failure here would surface as a 500, not an assertion mismatch.
        yaml(1).body(searchIndex(NAME, ENTITY, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        long secondId = allMarkId(1, NAME, "index-apply");

        assertThat(secondId).as("re-mark reissues a fresh, strictly-higher id").isGreaterThan(firstId);
    }

    private long allMarkId(long tenant, String name, String reason) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'ALL' AND reason = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
