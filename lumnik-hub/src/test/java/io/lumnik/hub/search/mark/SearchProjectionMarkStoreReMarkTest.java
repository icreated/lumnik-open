package io.lumnik.hub.search.mark;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc §1: a concurrent touch of an already-pending KEY mark must not be silently absorbed by
 * ON CONFLICT DO NOTHING — it must re-issue the same logical row with a fresh, strictly-higher id, so
 * a drain that already fetched the old id and is about to delete it does not also erase the signal
 * the re-mark represents.
 */
@QuarkusTest
class SearchProjectionMarkStoreReMarkTest {

    @Inject SearchProjectionMarkStore store;
    @Inject DataSource adminDs;

    @Test
    void reMarkingAnUndrainedKeyReissuesAHigherId() throws SQLException {
        store.markKeys(1L, "idx", "entity", List.of("k1"), "ingestion-run");
        long firstId = idOf(1L, "idx", "k1");

        store.markKeys(1L, "idx", "entity", List.of("k1"), "ingestion-run");
        long secondId = idOf(1L, "idx", "k1");

        assertThat(secondId).isGreaterThan(firstId);

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'idx' AND canonical_key = 'k1'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1); // still exactly one row — reissued, not duplicated
        }

        cleanup();
    }

    /** #719: a re-mark of an already-pending (tenant, index, kind, key) tuple must overwrite {@code
     *  reason} with the NEW trigger's name, not silently keep the reason from whichever trigger wrote
     *  the row first. Reproduces {@code SearchIndexEntityReapplyIT}'s failure directly against the
     *  store: a fresh SearchIndex apply writes an ALL mark with reason 'index-apply' (design §4 row
     *  4a); an entity re-apply that leaves it valid re-marks the SAME tuple with reason 'entity-reapply'
     *  (design §4 row 2) — a caller filtering marks by THIS reason (as the entity-reapply trigger's own
     *  acceptance test does) must find it, not the stale 'index-apply' reason from the first write. */
    @Test
    void reMarkingAnUndrainedAllMarkWithADifferentReasonOverwritesTheReason() throws SQLException {
        store.markAll(719_001L, "reason-idx", "entity", "index-apply");
        assertThat(reasonOfAllMark(719_001L, "reason-idx")).isEqualTo("index-apply");

        store.markAll(719_001L, "reason-idx", "entity", "entity-reapply");
        assertThat(reasonOfAllMark(719_001L, "reason-idx"))
            .as("a re-mark's own trigger name must replace the stale reason from the first write")
            .isEqualTo("entity-reapply");

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = 719001 AND search_index = 'reason-idx'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1); // still exactly one row — reissued, not duplicated
        }

        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 719001 AND search_index = 'reason-idx'");
        }
    }

    /** UNLIKE {@code markAll}'s no-Connection (admin-only) path fixed above, {@code markKeys}' own
     *  re-mark deliberately keeps the FIRST reason. {@code markKeys(Connection c, ...)} may run on a
     *  caller's own connection, which V57's comment says can be the request-scoped, lumnik_app one —
     *  {@code io.lumnik.hub.search.SearchProjectionMarkReMarkRlsIT} proves that exact boundary already
     *  applies to markAll's Connection overload (`GRANT UPDATE (id, marked_at)` only), and markKeys shares the same table
     *  and the same posture: widening its SET clause the way markAll's admin-only path was widened
     *  would reintroduce a permission error for a lumnik_app caller, the day one calls it. */
    @Test
    void reMarkingAnUndrainedKeyKeepsTheFirstReason() throws SQLException {
        store.markKeys(719_002L, "reason-idx-key", "entity", List.of("k1"), "ingestion-run");
        store.markKeys(719_002L, "reason-idx-key", "entity", List.of("k1"), "resolution-decision");

        assertThat(reasonOfKeyMark(719_002L, "reason-idx-key", "k1"))
            .as("markKeys' re-mark stays append-only for reason — only the admin-only paths (markAll, markKeysAsAdmin) overwrite it")
            .isEqualTo("ingestion-run");

        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 719002 AND search_index = 'reason-idx-key'");
        }
    }

    /** #852: the admin-pool scanner's own variant relabels, like markAll's admin path (#719) — the narrow
     *  variant above stays for callers that may hold a request-scoped lumnik_app connection. */
    @Test
    void reMarkingAnUndrainedKeyAsAdminOverwritesTheReasonAndStaysOneRow() throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            store.markKeysAsAdmin(c, 852_001L, "reason-idx-admin", "entity", List.of("k1"), "ingestion-run");
            long firstId = idOf(852_001L, "reason-idx-admin", "k1");
            store.markKeysAsAdmin(c, 852_001L, "reason-idx-admin", "entity", List.of("k1"), "lookup-change");

            assertThat(reasonOfKeyMark(852_001L, "reason-idx-admin", "k1")).isEqualTo("lookup-change");
            assertThat(idOf(852_001L, "reason-idx-admin", "k1")).as("still reissued with a higher id").isGreaterThan(firstId);
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM core.search_projection_mark "
                    + "WHERE tenant_id = 852001 AND search_index = 'reason-idx-admin'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 852001 AND search_index = 'reason-idx-admin'");
        }
    }

    private String reasonOfAllMark(long tenantId, String index) throws SQLException {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT reason FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'ALL'")) {
            ps.setLong(1, tenantId);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String reasonOfKeyMark(long tenantId, String index, String key) throws SQLException {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT reason FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND canonical_key = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, index);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** Copilot review, #671: a duplicate canonical key within the SAME {@code markKeys} call used to
     *  reach Postgres as two VALUES rows both targeting the same {@code ON CONFLICT DO UPDATE} row in
     *  one statement — refused outright ("ON CONFLICT DO UPDATE command cannot affect row a second
     *  time"), a hard failure a real caller (a bug in a future entity/reconciliation path) could hit
     *  even though today's own callers happen to already pass duplicate-free lists. Proves the same
     *  call now succeeds and still leaves exactly one row per distinct key. */
    @Test
    void aDuplicateKeyWithinOneCallNeverFailsTheBatchInsert() throws SQLException {
        store.markKeys(1L, "dup-idx", "entity", List.of("dup-k1", "dup-k1", "dup-k2"), "ingestion-run");

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'dup-idx'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).as("one row per DISTINCT key, not one per list entry").isEqualTo(2);
        }

        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'dup-idx'");
        }
    }

    /** #703's own lag computation: {@link SearchProjectionMarkStore#oldestPendingMarkedAtAdmin()}
     *  groups by (tenant, index) taking the MIN(marked_at) — a second, later mark for the SAME pair
     *  must not push the oldest value forward. */
    @Test
    void oldestPendingMarkedAtAdminGroupsByTenantAndIndexTakingTheMinimum() throws SQLException {
        store.markKeys(703_008_001L, "lag-idx", "widget", List.of("k1"), "test");
        Instant k1MarkedAt = markedAtOf(703_008_001L, "lag-idx", "k1");

        store.markKeys(703_008_001L, "lag-idx", "widget", List.of("k2"), "test"); // a second, later mark for the same pair

        Map<Long, Map<String, Instant>> result = store.oldestPendingMarkedAtAdmin();

        assertThat(result.get(703_008_001L)).containsKey("lag-idx");
        // Proves MIN, not merely "some row's timestamp before now": k2's own mark is strictly later
        // than k1's, so this exact equality would fail were the aggregate to return k2's value (or
        // any row other than the genuinely oldest one) instead.
        assertThat(result.get(703_008_001L).get("lag-idx")).isEqualTo(k1MarkedAt);

        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 703008001 AND search_index = 'lag-idx'");
        }
    }

    private Instant markedAtOf(long tenantId, String index, String key) throws SQLException {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT marked_at FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND canonical_key = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, index);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    /** {@link SearchProjectionMarkStore#oldestPendingMarkedAtForTenant(long)} — the same aggregate,
     *  narrowed in SQL to one tenant: another tenant's pending mark for the same index name must
     *  never leak into this tenant's own map. */
    @Test
    void oldestPendingMarkedAtForTenantExcludesOtherTenants() throws SQLException {
        store.markKeys(703_008_002L, "lag-idx-2", "widget", List.of("k1"), "test");
        store.markKeys(703_008_003L, "lag-idx-2", "widget", List.of("k1"), "test");

        assertThat(store.oldestPendingMarkedAtForTenant(703_008_002L)).containsOnlyKeys("lag-idx-2");

        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE search_index = 'lag-idx-2'");
        }
    }

    private long idOf(long tenantId, String index, String key) throws SQLException {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? AND canonical_key = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, index);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void cleanup() throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'idx'");
        }
    }
}
