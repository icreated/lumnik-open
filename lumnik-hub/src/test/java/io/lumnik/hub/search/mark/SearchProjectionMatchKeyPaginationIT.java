package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Copilot review (post-#671): {@code distinctMatchKeys} used to be one unordered
 * {@code SELECT DISTINCT ... LIMIT matchKeysBatchSize} — with no stable row order across repeated
 * calls, and {@code processTable} leaving the watermark unchanged whenever the batch was truncated
 * (to avoid skipping whatever the {@code LIMIT} cut off), a table with more distinct match tuples
 * than {@code matchKeysBatchSize} could keep re-fetching the SAME first N tuples forever and never
 * mark the rest of the backlog at all — a permanent stall, not merely a slower drain.
 *
 * <p>Proves the fix end to end: with {@code matchKeysBatchSize} shrunk to force multiple pages, a
 * SINGLE {@code processTenant} call still marks every distinct tuple, not just the first page's
 * worth — {@code SearchProjectionRunHook} is constructed directly here (never the shared CDI
 * singleton) specifically so shrinking this field can never leak into any other test in the suite.
 */
@QuarkusTest
class SearchProjectionMatchKeyPaginationIT {

    private static final String REASON = "ingestion-run";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;

    private SearchProjectionRunHook hookWithSmallPages(int pageSize) {
        SearchProjectionRunHook hook = new SearchProjectionRunHook();
        hook.ds = adminDs;
        hook.registry = registry;
        hook.markStore = markStore;
        hook.watermarkStore = watermarkStore;
        hook.matchKeysBatchSize = pageSize;
        return hook;
    }

    @Test
    void aBacklogLargerThanOnePageIsFullyMarkedInOneRun() throws Exception {
        long tenant = 671_930_001L;
        String entityName = "SearchPage701" + System.nanoTime();
        String table = "t_search_page_701_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-page");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        // 5 distinct match tuples (code alone is the match column), a page size of 2 -- 3 pages.
        insertRow(adminDs, table, tenant, "c1", "Alpha");
        insertRow(adminDs, table, tenant, "c2", "Beta");
        insertRow(adminDs, table, tenant, "c3", "Gamma");
        insertRow(adminDs, table, tenant, "c4", "Delta");
        insertRow(adminDs, table, tenant, "c5", "Epsilon");

        SearchProjectionRunHook hook = hookWithSmallPages(2);
        hook.processTenant(tenant);

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("all 5 distinct tuples marked in one run, not just the first page's worth")
            .isEqualTo(5L);
    }
}
