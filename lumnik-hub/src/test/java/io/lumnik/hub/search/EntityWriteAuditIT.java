package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static io.lumnik.hub.search.SearchIndexItSupport.count;
import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1193: an Entity apply and delete leave a row in the audit trail, and so does every SearchIndex an
 * entity delete takes with it — in the tenant that owned it, not only the deleting one.
 */
@QuarkusTest
class EntityWriteAuditIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    @Test
    void anApplyIsRecordedWithTheManifestDigest() throws Exception {
        String name = "AuditApplyEntity" + System.nanoTime();
        String manifest = entity(adminDs, name, "t_" + name.toLowerCase(Locale.ROOT) + "_erp", List.of("sales"), "code", "name");

        yaml(1).body(manifest).post("/api/entities/apply").then().statusCode(200);

        assertThat(rows(1, "Entity", name, "APPLY")).isEqualTo(1);
        assertThat(changes(1, "Entity", name, "APPLY")).contains(AuditRecorder.sha256(manifest));
    }

    @Test
    void aDeleteIsRecordedWithEverySearchIndexItTookInItsOwnTenant() throws Exception {
        String name = "AuditDeleteEntity" + System.nanoTime();
        String table = "t_" + name.toLowerCase(Locale.ROOT) + "_erp";
        yaml(1).body(entity(adminDs, name, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexA = "audit-cascade-a-" + System.nanoTime();
        String indexB = "audit-cascade-b-" + System.nanoTime();
        yaml(1).body(searchIndex(indexA, name, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml(2).body(searchIndex(indexB, name, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/entities/" + name).then().statusCode(204);

        assertThat(rows(1, "Entity", name, "DELETE")).isEqualTo(1);
        assertThat(rows(1, "SearchIndex", indexA, "DELETE")).isEqualTo(1);
        assertThat(rows(2, "SearchIndex", indexB, "DELETE")).as("audited in the tenant that owned it").isEqualTo(1);
        assertThat(changes(2, "SearchIndex", indexB, "DELETE")).contains(name);
        // The purge obligations ride the same statement in a CTE no one reads: PostgreSQL runs a
        // data-modifying CTE to completion all the same, and the audit rows must not replace them.
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index_purge WHERE tenant_id = ? AND name = ?",
            1L, indexA)).as("tenant 1's purge obligation").isEqualTo(1);
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index_purge WHERE tenant_id = ? AND name = ?",
            2L, indexB)).as("tenant 2's purge obligation").isEqualTo(1);
    }

    private long rows(long tenant, String type, String key, String operation) {
        return count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE tenant_id = ? AND entity_type = ? AND entity_key = ? AND operation = ?",
            tenant, type, key, operation);
    }

    private String changes(long tenant, String type, String key, String operation) {
        return Jdbc.queryOne(adminDs, "EntityWriteAuditIT.changes",
            "SELECT changes::text FROM core.audit_outbox "
                + "WHERE tenant_id = ? AND entity_type = ? AND entity_key = ? AND operation = ?",
            ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, type);
                ps.setString(3, key);
                ps.setString(4, operation);
            },
            rs -> rs.getString(1)).orElseThrow();
    }
}
