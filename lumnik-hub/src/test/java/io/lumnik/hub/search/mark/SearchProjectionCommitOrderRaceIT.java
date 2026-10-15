package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code-review finding (post-#671): {@code _ingested_at} is {@code DEFAULT now()} at transaction
 * START, not commit ({@code DynamicTableWriter}) — a longer transaction that started earlier can
 * commit its row AFTER a shorter, later-started transaction's row already advanced the watermark
 * past it, permanently hiding the earlier transaction's row from every future {@code _ingested_at >
 * watermark} scan. Proves {@link SearchProjectionRunHook}'s {@code commitOrderSafeCutoff} closes
 * this: while the older transaction is still open, the watermark must not advance past its {@code
 * xact_start}, and once it commits, the very next run catches its row up.
 */
@QuarkusTest
class SearchProjectionCommitOrderRaceIT {

    private static final String REASON = "ingestion-run";

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private void fireRun(long tenant) throws SQLException {
        hook.processTenant(tenant);
    }

    private Instant watermarkValue(long tenant, String qualifiedTable) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT watermark FROM core.search_projection_mark_watermark "
               + "WHERE tenant_id = ? AND table_name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, qualifiedTable);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    @Test
    void watermarkNeverAdvancesPastAnOlderStillOpenTransaction() throws Exception {
        long tenant = 671_701_001L;
        String entityName = "CommitOrderEntity" + System.nanoTime();
        String table = "t_commit_order_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-commit-order");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        Instant beforeOlder = Instant.now();

        // The "older" transaction: begins first, inserts its row, then stays open — its _ingested_at
        // is stamped under its own xact_start, but it will not commit until well after the "newer"
        // transaction below already has.
        Connection older = adminDs.getConnection();
        older.setAutoCommit(false);
        try (PreparedStatement ps = older.prepareStatement(
                "INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, "C-OLD");
            ps.setString(3, "Older");
            ps.executeUpdate();
        }

        // The "newer" transaction: starts after, commits immediately -- first to become visible.
        try (Connection newer = adminDs.getConnection();
             PreparedStatement ps = newer.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, "C-NEW");
            ps.setString(3, "Newer");
            ps.executeUpdate();
        }

        try {
            fireRun(tenant);

            assertThat(markCount(adminDs, tenant, indexName, REASON))
                .as("only the already-committed, newer row is visible to this scan")
                .isEqualTo(1L);
            assertThat(watermarkValue(tenant, "connector." + table))
                .as("must not advance past the still-open older transaction's start, or its row -- once "
                  + "committed -- would be permanently hidden from every future scan")
                .isBefore(beforeOlder);
        } finally {
            older.commit();
            older.close();
        }

        fireRun(tenant);

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("the older row, now committed, is caught up by the very next run")
            .isEqualTo(2L);
    }
}
