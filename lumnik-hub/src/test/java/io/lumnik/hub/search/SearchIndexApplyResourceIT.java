package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.search.SearchIndexItSupport.auditRows;
import static io.lumnik.hub.search.SearchIndexItSupport.count;
import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.freeEntities;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * #669 apply door, unscoped caller under dev-bypass: the ordered refusals (parse → unknown keys →
 * entity → canonical fields → scope → upsert → audit) and the round trip through the scope-bound
 * list. Scope refusals live in {@link SearchIndexScopeIT}, which needs real bearer tokens.
 */
@QuarkusTest
class SearchIndexApplyResourceIT {

    private static final String ENTITY = "SearchApplyCustomer669";
    private static final String TABLE = "t_searchapply669_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** Tenant AND user: AuditRecorder writes actor_id from X-User-ID. */
    private static RequestSpecification yaml() {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @BeforeEach
    void applyEntity() throws Exception {
        // One SearchIndex per Entity (#832): every test here shares ENTITY, so each starts with it free.
        freeEntities(adminDs, ENTITY);
        yaml().body(entity(adminDs, ENTITY, TABLE, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    @Test
    void aValidManifestIsStoredValidAndListed() throws Exception {
        String name = unique("customers");
        yaml().body(searchIndex(name, ENTITY, "name", "city"))
            .post("/api/search-indexes/apply")
            .then().statusCode(200)
            .body("name", equalTo(name))
            .body("entity", equalTo(ENTITY))
            .body("status", equalTo("valid"));

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + name + "' }", hasSize(1))
            .body("find { it.name == '" + name + "' }.entity", equalTo(ENTITY))
            .body("find { it.name == '" + name + "' }.language", equalTo("simple"))
            .body("find { it.name == '" + name + "' }.status", equalTo("valid"));

        assertThat(indexRows(adminDs, 1, name)).isEqualTo(1);
        assertThat(auditRows(adminDs, name, "APPLY")).isEqualTo(1);
    }

    @Test
    void aDeclaredTitleFieldSurvivesIntoTheStoredManifest() throws Exception {
        String name = unique("customers-title");
        yaml().body(searchIndex(name, ENTITY, "name", "city").replace("  fields:\n", "  titleField: city\n  fields:\n"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 AND name = ? "
            + "AND manifest->>'titleField' = 'city'", name)).isEqualTo(1);
    }

    @Test
    void aManifestWithoutTitleFieldStaysAsItWas() throws Exception {
        String name = unique("customers-notitle");
        yaml().body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply").then().statusCode(200);
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 AND name = ? "
            + "AND NOT jsonb_exists(manifest, 'titleField')", name)).isEqualTo(1);
    }

    @Test
    void declaredLinksSurviveIntoTheStoredManifest() throws Exception {
        String name = unique("customers-links");
        yaml().body(searchIndex(name, ENTITY, "name", "city").replace("  fields:\n",
                "  links:\n    - field: city\n      url: \"https://erp.example.com/c/{city}\"\n  fields:\n"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 AND name = ? "
            + "AND manifest->'links'->0->>'field' = 'city' "
            + "AND manifest->'links'->0->>'url' = 'https://erp.example.com/c/{city}'", name)).isEqualTo(1);
    }

    @Test
    void aManifestWithoutLinksStoresNoLinksKey() throws Exception {
        String name = unique("customers-nolinks");
        yaml().body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply").then().statusCode(200);
        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 AND name = ? "
            + "AND NOT jsonb_exists(manifest, 'links')", name)).isEqualTo(1);
    }

    @Test
    void aLinkWithAPlaceholderThatIsNotAFieldIsRefusedAtTheDoor() {
        String name = unique("customers-badlink");
        yaml().body(searchIndex(name, ENTITY, "name", "city").replace("  fields:\n",
                "  links:\n    - field: city\n      url: \"https://erp.example.com/c/{ghost}\"\n  fields:\n"))
            .post("/api/search-indexes/apply").then().statusCode(400)
            .body("error", containsString("spec.links[0].url"));
    }

    @Test
    void aTitleFieldThatIsNotADeclaredFieldIsRefused() {
        String name = unique("customers-badtitle");
        yaml().body(searchIndex(name, ENTITY, "name").replace("  fields:\n", "  titleField: city\n  fields:\n"))
            .post("/api/search-indexes/apply").then().statusCode(400)
            .body("error", containsString("spec.titleField"));
    }

    @Test
    void theEntityIsStoredUnderItsCanonicalSpelling() {
        String name = unique("customers-case");
        yaml().body(searchIndex(name, ENTITY.toLowerCase(), "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(200)
            .body("entity", equalTo(ENTITY));
    }

    @Test
    void reApplyingTheSameNameUpdatesInPlace() throws Exception {
        String name = unique("customers-reapply");
        yaml().body(searchIndex(name, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml().body(searchIndex(name, ENTITY, "name", "city").replace("  entity:", "  language: french\n  entity:"))
            .post("/api/search-indexes/apply")
            .then().statusCode(200);

        assertThat(indexRows(adminDs, 1, name)).isEqualTo(1);
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/search-indexes")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.language", equalTo("french"));
        assertThat(auditRows(adminDs, name, "APPLY")).isEqualTo(2);
    }

    @Test
    void aSecondIndexOverTheSameEntityIsRefusedNamingTheHolder() throws Exception {
        String holder = unique("holder");
        String second = unique("second");
        yaml().body(searchIndex(holder, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        yaml().body(searchIndex(second, ENTITY, "city"))
            .post("/api/search-indexes/apply")
            .then().statusCode(409)
            .body("error", containsString("'" + holder + "'"))
            .body("error", containsString(ENTITY))
            .body("error", containsString("one SearchIndex per Entity"));

        assertThat(indexRows(adminDs, 1, second)).isZero();
        assertThat(indexRows(adminDs, 1, holder)).isEqualTo(1);
        assertThat(auditRows(adminDs, second, "APPLY")).isZero();
    }

    @Test
    void theEntityIsHeldWhateverTheSpellingOfItsName() throws Exception {
        yaml().body(searchIndex(unique("holder-case"), ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml().body(searchIndex(unique("second-case"), ENTITY.toLowerCase(), "city"))
            .post("/api/search-indexes/apply")
            .then().statusCode(409);
    }

    @Test
    void anIndexMovedOntoAnEntityAnotherIndexHoldsIsRefusedAndStaysWhereItWas() throws Exception {
        String otherEntity = "SearchApplyOther832";
        yaml().body(entity(adminDs, otherEntity, "t_searchapply832_other", List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
        freeEntities(adminDs, otherEntity);
        String holder = unique("holder-move");
        String mover = unique("mover");
        yaml().body(searchIndex(holder, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml().body(searchIndex(mover, otherEntity, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        yaml().body(searchIndex(mover, ENTITY, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(409)
            .body("error", containsString("'" + holder + "'"));

        assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 AND name = ? "
            + "AND lower(entity) = lower(?)", mover, otherEntity)).isEqualTo(1);
    }

    /**
     * The advisory lock is what keeps the door honest under concurrency: without it both applies read "no
     * holder", both insert, and the loser trips the unique index into a 500. Rounds, because a race is only
     * probable.
     */
    @Test
    void twoNamesAppliedAtOnceOverOneEntityGiveOneWinnerAndOneRefusal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                freeEntities(adminDs, ENTITY);
                String a = unique("race-a");
                String b = unique("race-b");
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Response> fa = pool.submit(() -> applyOnSignal(start, a));
                Future<Response> fb = pool.submit(() -> applyOnSignal(start, b));
                Response ra = fa.get(30, TimeUnit.SECONDS);
                Response rb = fb.get(30, TimeUnit.SECONDS);

                assertThat(List.of(ra.statusCode(), rb.statusCode())).as("round %d", round)
                    .containsExactlyInAnyOrder(200, 409);
                String winner = ra.statusCode() == 200 ? a : b;
                Response loser = ra.statusCode() == 409 ? ra : rb;
                assertThat(loser.jsonPath().getString("error")).as("round %d", round).contains("'" + winner + "'");
                assertThat(count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = 1 "
                    + "AND lower(entity) = lower(?)", ENTITY)).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Response applyOnSignal(CyclicBarrier start, String name) throws Exception {
        String body = searchIndex(name, ENTITY, "name");
        start.await(10, TimeUnit.SECONDS);
        return yaml().body(body).post("/api/search-indexes/apply");
    }

    @Test
    void theEntityIsFreeAgainOnceItsIndexIsDeleted() throws Exception {
        String holder = unique("holder-rm");
        String next = unique("next");
        yaml().body(searchIndex(holder, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/search-indexes/" + holder).then().statusCode(204);

        yaml().body(searchIndex(next, ENTITY, "city")).post("/api/search-indexes/apply").then().statusCode(200);
    }

    @Test
    void anUnknownKeyIsRefusedWithTheHonoredSurface() throws Exception {
        String name = unique("customers-unknown");
        yaml().body(searchIndex(name, ENTITY, "name").replace("      match: fuzzy", "      match: fuzzy\n      boost: 2"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("spec.fields[0].boost"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    @Test
    void anUnknownEntityIsRefused() throws Exception {
        String name = unique("customers-noentity");
        yaml().body(searchIndex(name, "NoSuchEntity669", "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("entity 'NoSuchEntity669' not found"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    @Test
    void aFieldThatIsNotACanonicalColumnIsRefused() throws Exception {
        String name = unique("customers-badfield");
        yaml().body(searchIndex(name, ENTITY, "name", "salary"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("'salary' is not a canonical column"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    /** Another instance dropped `city` from the entity; this instance's cache still has it. */
    @Test
    void aColumnDroppedByAnotherInstanceIsRefusedDespiteAStaleCache() throws Exception {
        String entityName = "SearchApplyDropped669";
        String table = "t_searchapply669_dropped";
        yaml().body(entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
        assertThat(Jdbc.update(adminDs, "another instance drops city",
            "UPDATE connector.entity_manifest SET yaml_text = ?, updated_at = now() WHERE name = lower(?)",
            ps -> {
                ps.setString(1, SearchIndexItSupport.entityManifest(entityName, table, List.of("sales"), "code", "name"));
                ps.setString(2, entityName);
            })).isEqualTo(1);

        String name = unique("customers-stale-column");
        yaml().body(searchIndex(name, entityName, "name", "city"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("'city' is not a canonical column"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    @Test
    void aSourceColumnNameIsNotACanonicalColumn() {
        yaml().body(searchIndex(unique("customers-srccol"), ENTITY, "src_name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("'src_name' is not a canonical column"));
    }

    @Test
    void declaredScopesAndRawTablesAreRefusedByName() {
        String scoped = searchIndex(unique("customers-scoped"), ENTITY, "name")
            .replace("  entity:", "  scopes: [crm]\n  entity:");
        yaml().body(scoped).post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("inherited from the entity"));

        String raw = searchIndex(unique("customers-raw"), ENTITY, "name")
            .replace("  entity:", "  table: " + TABLE + "\n  entity:");
        yaml().body(raw).post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("kind: Entity"));
    }

    @Test
    void aWrongApiVersionIsRefused() {
        yaml().body(searchIndex(unique("customers-apiv"), ENTITY, "name")
                .replace("search.lumnik.io/v1", "apps.lumnik.io/v1"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("apiVersion"));
    }

    @Test
    void aNameThatIsNotAUrlSafeSlugIsRefused() {
        yaml().body(searchIndex("Customers Index", ENTITY, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("metadata.name"));
    }

    @Test
    void theNameApplyIsReservedBecauseItShadowsTheDoor() {
        yaml().body(searchIndex("apply", ENTITY, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(400)
            .body("error", containsString("metadata.name"));
    }

    @Test
    void aMalformedDocumentIsA400NotA500() {
        yaml().body("- just\n- a list\n").post("/api/search-indexes/apply").then().statusCode(400);
        yaml().body("").post("/api/search-indexes/apply").then().statusCode(400);
    }

    /**
     * "Configuration only" observed, not inferred: the capability staying not_installed says nothing
     * about work the doors might have queued. So the two places work would show — a connector run,
     * an outbox event — are counted before and after; the capability is a separate assertion.
     */
    @Test
    // PR #734 review finding: asserts the no-provider posture itself (search.capability's
    // "unavailable"/"not_installed"), which cannot hold under -Psearch — see
    // PlatformHealthResourceSearchIT's identical note.
    @DisabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
    void applyAndDeleteStartNoBackendWorkWithoutAProvider() throws Exception {
        long runsBefore = count(adminDs, "SELECT count(*) FROM connector.connector_run");
        long eventsBefore = count(adminDs, "SELECT count(*) FROM core.event_outbox");

        String name = unique("customers-noprovider");
        yaml().body(searchIndex(name, ENTITY, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/search-indexes/" + name)
            .then().statusCode(204);

        assertThat(count(adminDs, "SELECT count(*) FROM connector.connector_run"))
            .as("no connector run started").isEqualTo(runsBefore);
        assertThat(count(adminDs, "SELECT count(*) FROM core.event_outbox"))
            .as("no event queued").isEqualTo(eventsBefore);

        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/search/capability")
            .then().statusCode(200)
            .body("state", equalTo("unavailable"))
            .body("reason", equalTo("not_installed"));
    }
}
