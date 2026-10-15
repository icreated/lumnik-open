package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static io.lumnik.hub.search.SearchIndexItSupport.freeEntities;
import static io.lumnik.hub.search.SearchIndexItSupport.auditRows;
import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.purgeRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

/**
 * #669 DELETE and the fail-closed purge contract #701 consumes: the declaration disappears and ONE
 * purge obligation is recorded, in the same transaction. The obligation outlives the declaration —
 * a re-apply of the same name is a new declaration, never a cancellation of the pending purge.
 */
@QuarkusTest
class SearchIndexDeleteIT {

    private static final String ENTITY = "SearchDeleteCustomer669";
    private static final String TABLE = "t_searchdelete669_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml() {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void apply(String name) {
        yaml().body(searchIndex(name, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
    }

    private static void delete(String name, int status) {
        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/search-indexes/" + name).then().statusCode(status);
    }

    @BeforeEach
    void applyEntity() throws Exception {
        freeEntities(adminDs, ENTITY);
        yaml().body(entity(adminDs, ENTITY, TABLE, List.of("sales"), "code", "name"))
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    @Test
    void deleteRemovesTheDeclarationAndRecordsOnePurgeObligation() throws Exception {
        String name = unique("purge");
        apply(name);

        delete(name, 204);

        assertThat(indexRows(adminDs, 1, name)).isZero();
        assertThat(purgeRows(adminDs, 1, name)).isEqualTo(1);
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT entity, requested_by, requested_at FROM core.search_index_purge "
                     + "WHERE tenant_id = 1 AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("entity")).isEqualTo(ENTITY);
                assertThat(rs.getLong("requested_by")).as("actor from X-User-ID").isEqualTo(1L);
                assertThat(rs.getTimestamp("requested_at")).isNotNull();
            }
        }
        assertThat(auditRows(adminDs, name, "DELETE")).isEqualTo(1);
    }

    /**
     * The "one transaction" half of the contract, which the happy path cannot prove: an implementation
     * that deleted the declaration and then wrote the obligation separately would pass every other test
     * here, and lose the purge — the declaration gone for good — whenever the second write failed. A
     * trigger poisons the purge INSERT for this one name only; the delete must then fail as a whole.
     */
    @Test
    void aFailedPurgeWriteRollsTheWholeDeleteBack() throws Exception {
        String name = unique("poisoned");
        apply(name);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION core.search_index_purge_poison_669() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.name = '" + name + "' THEN RAISE EXCEPTION 'injected purge failure'; END IF; "
                + "RETURN NEW; END $$");
            st.execute("CREATE TRIGGER search_index_purge_poison_669 BEFORE INSERT ON core.search_index_purge "
                + "FOR EACH ROW EXECUTE FUNCTION core.search_index_purge_poison_669()");
        }
        try {
            int status = given().headers(TestIdentity.tenantAndUser(1))
                .delete("/api/search-indexes/" + name)
                .then().extract().statusCode();
            assertThat(status).as("the delete must fail, not report success").isGreaterThanOrEqualTo(500);

            assertThat(indexRows(adminDs, 1, name)).as("declaration rolled back").isEqualTo(1);
            assertThat(purgeRows(adminDs, 1, name)).as("no obligation").isZero();
            assertThat(auditRows(adminDs, name, "DELETE")).as("no audit of a delete that did not happen").isZero();
        } finally {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS search_index_purge_poison_669 ON core.search_index_purge");
                st.execute("DROP FUNCTION IF EXISTS core.search_index_purge_poison_669()");
            }
        }
        // Once the injection is gone, the same delete goes through normally.
        delete(name, 204);
        assertThat(purgeRows(adminDs, 1, name)).isEqualTo(1);
    }

    @Test
    void aDeletedIndexIsGoneFromTheList() {
        String name = unique("gone");
        apply(name);
        delete(name, 204);

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + name + "' }", hasSize(0));
    }

    @Test
    void deletingAnUnknownNameIs404AndRecordsNothing() throws Exception {
        String name = unique("never-applied");
        delete(name, 404);
        assertThat(purgeRows(adminDs, 1, name)).isZero();
        assertThat(auditRows(adminDs, name, "DELETE")).isZero();
    }

    @Test
    void aSecondDeleteIs404AndDoesNotDuplicateTheObligation() throws Exception {
        String name = unique("twice");
        apply(name);
        delete(name, 204);
        delete(name, 404);
        assertThat(purgeRows(adminDs, 1, name)).isEqualTo(1);
    }

    @Test
    void reApplyingADeletedNameIsANewDeclarationAndKeepsThePendingPurge() throws Exception {
        String name = unique("reborn");
        apply(name);
        delete(name, 204);

        apply(name);

        assertThat(indexRows(adminDs, 1, name)).isEqualTo(1);
        assertThat(purgeRows(adminDs, 1, name)).as("the older obligation is still honoured").isEqualTo(1);
    }
}
