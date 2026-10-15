package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityDefinitionLock;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.search.SearchIndexItSupport.freeEntities;
import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.entityManifest;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #669: a SearchIndex apply and an Entity (re-)apply over the same entity are coordinated by
 * {@link EntityDefinitionLock} — shared for the SearchIndex write, exclusive for the entity compile —
 * so neither can commit around the other. Each test holds one side of the lock on a raw admin
 * transaction, the way a still-open request would, and proves the other side waits for it.
 */
@QuarkusTest
class SearchIndexEntityConcurrencyIT {

    private static final String ENTITY = "SearchConcurrency669";
    private static final String TABLE = "t_searchconcurrency669_erp";
    private static final long WAIT_MS = 1500;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @BeforeEach
    void applyEntity() throws Exception {
        freeEntities(adminDs, ENTITY);
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body(entity(adminDs, ENTITY, TABLE, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private CompletableFuture<Integer> async(String path, String body) {
        return CompletableFuture.supplyAsync(() -> given().config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body(body).post(path).then().extract().statusCode(), pool);
    }

    /**
     * An uncommitted SearchIndex apply holds the shared side: an entity re-apply dropping a column must
     * wait for it, so its sweep runs after that declaration is visible — never beside it.
     */
    @Test
    void anOpenSearchIndexApplyHoldsTheEntityReApplyUntilItCommits() throws Exception {
        try (Connection open = adminDs.getConnection()) {
            open.setAutoCommit(false);
            EntityDefinitionLock.shared(open, ENTITY);

            CompletableFuture<Integer> reapply = async("/api/entities/apply",
                entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name"));
            Thread.sleep(WAIT_MS);
            assertThat(reapply).as("the entity compile waits for the open SearchIndex write").isNotDone();

            open.commit();
            assertThat(reapply.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Inject SearchIndexRevalidator revalidator;

    /** A valid index on `city`, and the one declaration the sweep reads for it. */
    private SearchIndexRevalidator.Declared declaredOnCity(String name) {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body(searchIndex(name, ENTITY, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        return revalidator.snapshot(ENTITY).stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    /** The stored status of one declaration. */
    private String status(String name) {
        return Jdbc.queryOne(adminDs, "status",
            "SELECT status FROM core.search_index WHERE tenant_id = 1 AND name = ?",
            ps -> ps.setString(1, name), rs -> rs.getString(1)).orElseThrow();
    }

    /**
     * An invalidation computed on E1 is still open when E2 (restoring the column) is applied: E2 must
     * wait for it, so the invalidation can never commit AFTER E2 and leave the index invalid against a
     * definition that has the column.
     */
    @Test
    void anOpenInvalidationHoldsTheNextEntityReApplyUntilItCommits() throws Exception {
        String e1 = entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name");
        String name = "open-invalidation-" + System.nanoTime();
        SearchIndexRevalidator.Declared d = declaredOnCity(name);
        // E1 (without city) is the stored definition, as its compile committed.
        Jdbc.update(adminDs, "E1 current",
            "UPDATE connector.entity_manifest SET yaml_text = ? WHERE name = lower(?)",
            ps -> { ps.setString(1, e1); ps.setString(2, ENTITY); });

        try (Connection invalidating = adminDs.getConnection()) {
            invalidating.setAutoCommit(false);
            assertThat(revalidator.invalidateIfUnchanged(invalidating, d, "E1 dropped city", ENTITY, e1)).isTrue();

            CompletableFuture<Integer> e2 = async("/api/entities/apply",
                entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name", "city"));
            Thread.sleep(WAIT_MS);
            assertThat(e2).as("E2's compile waits for the open invalidation").isNotDone();

            invalidating.commit();
            assertThat(e2.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        // Serialized: invalidated under E1, then E2 — one-way by design, a SearchIndex re-apply revalidates.
        assertThat(status(name)).isEqualTo("invalid");
    }

    /**
     * E2 is being compiled (exclusive held, definition rewritten) when E1's delayed invalidation runs:
     * it must wait, then see E2 committed and invalidate nothing.
     */
    @Test
    void anInvalidationWaitingOnTheNextCompileSeesItAndInvalidatesNothing() throws Exception {
        String e1 = entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name");
        String e2 = entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name", "city");
        String name = "waiting-invalidation-" + System.nanoTime();
        SearchIndexRevalidator.Declared d = declaredOnCity(name);
        Jdbc.update(adminDs, "E1 current",
            "UPDATE connector.entity_manifest SET yaml_text = ? WHERE name = lower(?)",
            ps -> { ps.setString(1, e1); ps.setString(2, ENTITY); });

        CompletableFuture<Boolean> invalidation;
        try (Connection compiling = adminDs.getConnection()) {
            compiling.setAutoCommit(false);
            EntityDefinitionLock.exclusive(compiling, ENTITY);
            try (PreparedStatement ps = compiling.prepareStatement(
                    "UPDATE connector.entity_manifest SET yaml_text = ? WHERE name = lower(?)")) {
                ps.setString(1, e2);
                ps.setString(2, ENTITY);
                ps.executeUpdate();
            }

            invalidation = CompletableFuture.supplyAsync(
                () -> revalidator.invalidateIfUnchanged(d, "E1 dropped city", ENTITY, e1), pool);
            Thread.sleep(WAIT_MS);
            assertThat(invalidation).as("the invalidation waits for E2's compile").isNotDone();

            compiling.commit();
        }
        assertThat(invalidation.get(10, TimeUnit.SECONDS)).as("E1 is no longer current").isFalse();
        assertThat(status(name)).isEqualTo("valid");
    }

    /**
     * An entity compile in flight holds the exclusive side and changes the definition: a SearchIndex
     * apply that validated the old one waits, then finds the entity changed and writes nothing (409).
     */
    @Test
    void aSearchIndexApplyWaitingOnAnEntityCompileIsRefusedWhenTheEntityChanged() throws Exception {
        String name = "concurrent-" + System.nanoTime();
        try (Connection compiling = adminDs.getConnection()) {
            compiling.setAutoCommit(false);
            EntityDefinitionLock.exclusive(compiling, ENTITY);
            try (PreparedStatement ps = compiling.prepareStatement(
                    "UPDATE connector.entity_manifest SET yaml_text = ?, updated_at = now() WHERE name = lower(?)")) {
                ps.setString(1, entityManifest(ENTITY, TABLE, List.of("sales"), "code", "name"));
                ps.setString(2, ENTITY);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            CompletableFuture<Integer> apply = async("/api/search-indexes/apply",
                searchIndex(name, ENTITY, "name", "city"));
            Thread.sleep(WAIT_MS);
            assertThat(apply).as("the SearchIndex write waits for the entity compile").isNotDone();

            compiling.commit();
            assertThat(apply.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }
}
