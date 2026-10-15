package io.lumnik.hub.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #940: {@code core.webhook_delivery} kept every delivery forever, payload and response body
 * included. The purger bounds it: finished deliveries past the window die, recent ones stay, a row the
 * retry sweep still owns is never touched, and a retention of {@code <= 0} keeps everything (the
 * explicit opt-out, like the audit purge).
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookDeliveryRetentionPurgerIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TENANT = 8940L;
    private static final String EP = "ret940-ep";

    @Inject WebhookDeliveryRetentionPurger purger;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private long endpointId;

    private void exec(String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM core.webhook_delivery WHERE tenant_id = " + TENANT);
        exec("DELETE FROM core.webhook_endpoint WHERE name = '" + EP + "'");
        exec("INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled) "
           + "VALUES (" + TENANT + ", '" + EP + "', 'http://localhost:1/x', 'generic', 'NONE', '{ret940.*}', FALSE)");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM core.webhook_endpoint WHERE name = '" + EP + "'")) {
            rs.next();
            endpointId = rs.getLong(1);
        }

        // finished: succeeded, or failed with every attempt spent
        delivery("ret940.old_ok", 120, true, 1, null);
        delivery("ret940.old_capped", 120, false, 3, null);
        // still the retry sweep's: a failure with attempts left, a first delivery not yet posted,
        // and a finished row somebody holds a lease on
        delivery("ret940.old_retryable", 120, false, 1, null);
        delivery("ret940.old_pending", 120, false, 0, null);
        delivery("ret940.old_leased", 120, false, 3, "now() + interval '5 minutes'");
        // inside the window
        delivery("ret940.recent_ok", 10, true, 1, null);
        delivery("ret940.recent_capped", 10, false, 3, null);
    }

    private void delivery(String type, int daysAgo, boolean succeeded, int attempts, String claimedUntil) throws Exception {
        exec("INSERT INTO core.webhook_delivery (tenant_id, endpoint_id, event_type, payload, attempted_at, "
           + "attempts, succeeded, claimed_until) VALUES (" + TENANT + ", " + endpointId + ", '" + type
           + "', '{}'::jsonb, now() - interval '" + daysAgo + " days', " + attempts + ", " + succeeded + ", "
           + (claimedUntil == null ? "NULL" : claimedUntil) + ")");
    }

    private Set<String> surviving() throws Exception {
        Set<String> types = new TreeSet<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_type FROM core.webhook_delivery WHERE tenant_id = " + TENANT)) {
            while (rs.next()) types.add(rs.getString(1).substring("ret940.".length()));
        }
        return types;
    }

    @Test
    void finished_deliveries_past_the_window_go_and_the_rest_stay() throws Exception {
        purger.purge();   // the default window is 90 days

        assertThat(surviving())
            .as("old_ok and old_capped are finished and old; the retry sweep's rows and the recent ones stay")
            .containsExactlyInAnyOrder("old_retryable", "old_pending", "old_leased", "recent_ok", "recent_capped");
    }

    @Test
    void a_window_of_zero_or_less_keeps_everything() throws Exception {
        purger.purgeWithRetention(0);
        purger.purgeWithRetention(-1);

        assertThat(surviving()).as("retention <= 0 is the explicit keep-forever").hasSize(7);
    }

    @Test
    void a_history_larger_than_a_batch_is_purged_batch_by_batch() throws Exception {
        for (int i = 0; i < 5; i++) delivery("ret940.bulk" + i, 200, true, 1, null);

        int purged = purger.purgeWithRetention(90, 2);

        assertThat(purged).as("7 finished rows over 90 days = 2 + 5, in batches of 2").isEqualTo(7);
        assertThat(surviving()).doesNotContain("old_ok", "old_capped", "bulk0", "bulk4");
    }

    /**
     * No index led with {@code attempted_at}: the daily cutoff would read the whole table (the reason
     * V40 gave the audit log one). {@code enable_seqscan}/{@code enable_sort} off take away every path
     * but an index scan, so this proves the path EXISTS — a test-sized table may rightly prefer a
     * scan; the history this reads is the case that matters.
     */
    @Test
    void the_cutoff_is_read_through_an_index_on_attempted_at() throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL enable_sort = off");
                try (PreparedStatement ps = c.prepareStatement(
                        "EXPLAIN (FORMAT JSON) " + WebhookDeliveryRetentionPurger.PURGE_BATCH_SQL)) {
                    ps.setInt(1, 90);
                    ps.setInt(2, 1000);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        collect(MAPPER.readTree(rs.getString(1)).get(0).path("Plan"), nodes);
                    }
                }
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }

        assertThat(nodes).as("the batch walks the oldest rows through the index: %s", nodes.get(0))
            .anyMatch(n -> "idx_webhook_delivery_attempted_at".equals(n.path("Index Name").asText()));
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        for (JsonNode child : node.path("Plans")) collect(child, out);
    }
}
