package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #701 phase 4, trigger row 1 (design doc §4 and §7 test 1): {@link SearchProjectionRunHook},
 * {@code @ObservesAsync ConnectorRunCompleted} for {@code match:} entities. Calls the package-visible
 * {@code processTenant} the observer delegates to rather than firing the CDI event and polling — same
 * idiom as {@code EntityResolutionRunHookTest}, since {@code @ObservesAsync} delivery timing is not what
 * this test exists to prove (and the observer is inert without a Search provider, #896).
 */
@QuarkusTest
class SearchProjectionIngestionIT {

    private static final String REASON = "ingestion-run";

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** Simulates a second ingestion run touching the same rows: bumps {@code _ingested_at}, like the
     *  writer's {@code ON CONFLICT (_row_hash) DO UPDATE SET _ingested_at = now()} path would. */
    private void bumpIngestedAt(String table, long tenant) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector." + table + " SET _ingested_at = now() WHERE tenant_id = ?")) {
            ps.setLong(1, tenant);
            ps.executeUpdate();
        }
    }

    private void fireRun(long tenant) throws SQLException {
        hook.processTenant(tenant);
    }

    // -- Design doc §7 test 1 ------------------------------------------------------------------------

    @Test
    void ingestionRunProducesOneKeyMarkPerDistinctMatchTuple() throws Exception {
        long tenant = 701_401;
        String entityName = "SearchIngest701A" + System.nanoTime();
        String table = "t_search_ingest_701a_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        String indexName = unique("mark-ingest-a");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        // 3 rows, one duplicate match tuple -> 2 distinct keys
        insertRow(adminDs, table, tenant, "C1", "Alpha");
        insertRow(adminDs, table, tenant, "C2", "Beta");
        insertRow(adminDs, table, tenant, "C1", "Alpha again");

        fireRun(tenant);

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("2 distinct match tuples (C1, C2) among 3 rows -> 2 KEY marks")
            .isEqualTo(2L);
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("a successful scan-and-mark pass advances the watermark, keyed by the schema-qualified "
              + "table name (V56's own convention)")
            .isEqualTo(1L);
    }

    @Test
    void secondIdenticalRunReMarksTheSameKeysWithoutGrowth() throws Exception {
        long tenant = 701_402;
        String entityName = "SearchIngest701B" + System.nanoTime();
        String table = "t_search_ingest_701b_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        String indexName = unique("mark-ingest-b");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        insertRow(adminDs, table, tenant, "C1", "Alpha");
        insertRow(adminDs, table, tenant, "C2", "Beta");

        fireRun(tenant);
        assertThat(markCount(adminDs, tenant, indexName, REASON)).isEqualTo(2L);

        // A second run over the SAME (unchanged) rows: _ingested_at bumps, exactly as the writer's
        // ON CONFLICT ... DO UPDATE SET _ingested_at = now() path does.
        bumpIngestedAt(table, tenant);
        fireRun(tenant);

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("idempotent: the UNIQUE (tenant_id, search_index, mark_kind, canonical_key) constraint, "
              + "not app logic, keeps this at 2 rather than growing")
            .isEqualTo(2L);
    }

    @Test
    void tableWithNoActiveSearchIndexProducesNoMarksAndNoWatermarkRow() throws Exception {
        long tenant = 701_403;
        String entityName = "SearchIngest701C" + System.nanoTime();
        String table = "t_search_ingest_701c_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        // Deliberately: no SearchIndex applied over this entity.

        insertRow(adminDs, table, tenant, "C1", "Alpha");

        fireRun(tenant);

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ?")) {
            ps.setLong(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no SearchIndex over this entity -> no marks").isZero();
            }
        }
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("a table nobody indexes is never scanned, so no watermark row either").isZero();
    }

    @Test
    void resolutionModeEntityProducesNothingFromThisHook() throws Exception {
        long tenant = 701_404;
        String entityName = "SearchIngest701DRes" + System.nanoTime();
        String table = "t_search_ingest_701d_res_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entityName, table);

        String indexName = unique("mark-ingest-res");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, \"CUSTOMER_ID\", \"NAME_COL\") VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, "cust-1");
            ps.setString(3, "Somebody");
            ps.executeUpdate();
        }

        fireRun(tenant);

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("resolution: entities have no canonical key until CascadeResolver runs -- row 1' is "
              + "not this hook's job")
            .isZero();
    }

    /**
     * Regression: two {@code match:} entities sourcing the SAME physical table used to race on that
     * table's single watermark row — whichever entity's {@code processSource} transaction committed
     * first advanced the watermark for the OTHER entity too, silently losing its marks for the very
     * rows just scanned. {@link SearchProjectionRunHook#process} now groups by table first, so both
     * entities are marked from the identical scanned range before the watermark moves once.
     */
    @Test
    void twoEntitiesSharingOneSourceTableBothGetMarkedFromTheSameScan() throws Exception {
        long tenant = 701_405;
        String table = "t_search_ingest_701e_shared_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        String entityA = "SearchIngest701EA" + System.nanoTime();
        String entityB = "SearchIngest701EB" + System.nanoTime();
        // Both entities source the same table; each keys on a different canonical column so their
        // match tuples (and therefore their marks) are genuinely independent, not incidentally equal.
        yaml(tenant).body(entityManifest(entityA, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(entityManifest(entityB, table, List.of("sales"), "name", "code"))
            .post("/api/entities/apply").then().statusCode(200);

        String indexA = unique("mark-ingest-shared-a");
        String indexB = unique("mark-ingest-shared-b");
        yaml(tenant).body(searchIndex(indexA, entityA, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        yaml(tenant).body(searchIndex(indexB, entityB, "code")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        insertRow(adminDs, table, tenant, "C1", "Alpha");
        insertRow(adminDs, table, tenant, "C2", "Beta");

        fireRun(tenant);

        assertThat(markCount(adminDs, tenant, indexA, REASON))
            .as("entity A, keyed on its own match column, marked from the shared scan").isEqualTo(2L);
        assertThat(markCount(adminDs, tenant, indexB, REASON))
            .as("entity B must NOT lose its marks to entity A having already advanced the watermark")
            .isEqualTo(2L);
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("one watermark row for the table, advanced once after BOTH entities were marked")
            .isEqualTo(1L);
    }
}
