package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.entityManifest;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * #669: an Entity re-apply that drops a column a SearchIndex uses turns that SearchIndex
 * {@code invalid} — it never refuses the Entity apply. Entity manifests are deployment-global, so the
 * re-check spans every tenant's SearchIndexes over that entity. Only a SearchIndex re-apply that
 * passes makes it {@code valid} again; restoring the column alone does not.
 */
@QuarkusTest
class SearchIndexEntityReapplyIT {

    private static final String ENTITY = "SearchReapplyCustomer669";
    private static final String TABLE = "t_searchreapply669_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    /** Marks of {@code mark_kind = 'ALL'} for this (tenant, search index), with the given reason. */
    private long allMarks(long tenant, String name, String reason) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark "
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

    /** The stored status, read as is — and exactly one row, so a vanished declaration fails loudly. */
    private String status(long tenant, String name) throws Exception {
        List<String> statuses = new ArrayList<>();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT status FROM core.search_index WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) statuses.add(rs.getString(1));
            }
        }
        assertThat(statuses).as("exactly one declaration for tenant %d / %s", tenant, name).hasSize(1);
        return statuses.get(0);
    }

    @Test
    void droppingAnIndexedColumnInvalidatesEveryTenantsIndexWithoutRefusingTheEntity() throws Exception {
        String name = "reapply-" + System.nanoTime();
        yaml(1).body(entity(adminDs, ENTITY, TABLE, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);

        yaml(1).body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
        yaml(2).body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));

        // Re-apply the entity without `city`: the Entity door must still answer 200.
        yaml(1).body(entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        assertThat(status(1, name)).isEqualTo("invalid");
        assertThat(status(2, name)).as("entity is global: tenant 2's index is re-checked too").isEqualTo("invalid");
        // Trigger 2 (design §4 row 2): an index that just turned invalid writes no re-project mark —
        // "an invalid declaration takes no part in Search until re-applied".
        assertThat(allMarks(1, name, "entity-reapply")).as("tenant 1's index turned invalid").isZero();
        assertThat(allMarks(2, name, "entity-reapply")).as("tenant 2's index turned invalid").isZero();
        given().headers(TestIdentity.tenantAndUser(1)).get("/api/search-indexes")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.status", equalTo("invalid"))
            .body("find { it.name == '" + name + "' }.invalidReason",
                containsString("'city' is not a canonical column of entity '" + ENTITY + "'"));

        // Restoring the column does not silently revalidate: the declaration must be re-applied.
        yaml(1).body(entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
        assertThat(status(1, name)).isEqualTo("invalid");

        yaml(1).body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
        given().headers(TestIdentity.tenantAndUser(1)).get("/api/search-indexes")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.status", equalTo("valid"))
            .body("find { it.name == '" + name + "' }.invalidReason", nullValue());
        assertThat(status(2, name)).as("tenant 2 re-applies on its own").isEqualTo("invalid");
    }

    @Inject SearchIndexRevalidator revalidator;

    /**
     * The sweep reads a declaration, then updates it. A re-apply — or a delete and re-creation — that
     * lands in between replaces the row; the sweep must not invalidate the NEW declaration with a
     * reason computed on the old YAML. The two phases are driven by hand to put the race in the gap.
     */
    @Test
    void theSweepNeverInvalidatesADeclarationReplacedSinceItWasRead() throws Exception {
        String entityName = ENTITY + "Race";
        String table = TABLE + "_race";
        String current = entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city");
        yaml(1).body(current).post("/api/entities/apply").then().statusCode(200);
        String replaced = "race-replaced-" + System.nanoTime();
        String recreated = "race-recreated-" + System.nanoTime();
        String untouched = "race-untouched-" + System.nanoTime();
        // One SearchIndex per Entity (#832) is per tenant, and the sweep is cross-tenant: one tenant each.
        yaml(1).body(searchIndex(replaced, entityName, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        yaml(2).body(searchIndex(recreated, entityName, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        yaml(3).body(searchIndex(untouched, entityName, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        List<SearchIndexRevalidator.Declared> read = revalidator.snapshot(entityName);

        // Between read and write: one declaration re-applied, one deleted and re-created.
        yaml(1).body(searchIndex(replaced, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        given().headers(TestIdentity.tenantAndUser(2)).delete("/api/search-indexes/" + recreated)
            .then().statusCode(204);
        yaml(2).body(searchIndex(recreated, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        for (SearchIndexRevalidator.Declared d : read) {
            boolean invalidated = revalidator.invalidateIfUnchanged(d, "stale reason from the old read", entityName, current);
            if (d.name().equals(untouched)) assertThat(invalidated).as("control: an untouched row is updated").isTrue();
            if (d.name().equals(replaced) || d.name().equals(recreated))
                assertThat(invalidated).as("%s was replaced after the read", d.name()).isFalse();
        }
        assertThat(status(1, replaced)).isEqualTo("valid");
        assertThat(status(2, recreated)).isEqualTo("valid");
        assertThat(status(3, untouched)).isEqualTo("invalid");
    }

    @Inject EntityManifestParser entityParser;

    /**
     * A re-apply that dropped `city` committed, then its callback stalled; meanwhile the entity was
     * re-applied WITH `city` and a fresh SearchIndex on `city` was declared. The stale callback must
     * not invalidate it: its reason is computed on a definition that is no longer the stored one.
     */
    @Test
    void aDelayedCallbackForAnOlderEntityVersionNeverInvalidatesAFreshIndex() throws Exception {
        String entityName = ENTITY + "Delayed";
        String table = TABLE + "_delayed";
        String withoutCity = entityManifest(entityName, table, List.of("sales"), "code", "name");
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
        String name = "delayed-" + System.nanoTime();
        yaml(1).body(searchIndex(name, entityName, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        // The old callback resumes now, carrying the definition it was fired for.
        revalidator.afterEntityApplied(entityParser.parse(withoutCity), withoutCity);
        assertThat(status(1, name)).as("the stored entity is no longer that version").isEqualTo("valid");

        // Control: when that version IS the stored one, the same callback invalidates.
        assertThat(Jdbc.update(adminDs, "make the old version current",
            "UPDATE connector.entity_manifest SET yaml_text = ? WHERE name = lower(?)",
            ps -> {
                ps.setString(1, withoutCity);
                ps.setString(2, entityName);
            })).isEqualTo(1);
        revalidator.afterEntityApplied(entityParser.parse(withoutCity), withoutCity);
        assertThat(status(1, name)).isEqualTo("invalid");
    }

    @Test
    void anEntityReApplyThatKeepsEveryIndexedColumnLeavesTheIndexValid() throws Exception {
        String name = "reapply-keep-" + System.nanoTime();
        String entityName = ENTITY + "Keep";
        String table = TABLE + "_keep";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
        yaml(1).body(searchIndex(name, entityName, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        yaml(1).body(entityManifest(entityName, table, List.of("sales", "magasin"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        assertThat(status(1, name)).isEqualTo("valid");
        // Trigger 2 (design §4 row 2): the declaration is still valid after the re-check, so the
        // re-apply writes exactly one full-reproject mark for it.
        assertThat(allMarks(1, name, "entity-reapply")).isEqualTo(1L);
    }
}
