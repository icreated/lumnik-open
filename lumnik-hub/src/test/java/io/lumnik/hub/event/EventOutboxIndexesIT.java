package io.lumnik.hub.event;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V36: guards the two indexes added on core.event_outbox for its two read paths —
 * EventStreamResource's SSE poll (created_at range + sort) and InboxResource's per-tenant
 * tail (tenant_id equality + id DESC sort). Flyway runs at boot against the DevServices
 * Postgres, so by the time this test runs V36 is already applied.
 */
@QuarkusTest
class EventOutboxIndexesIT {

    @Inject
    DataSource dataSource;

    @Test
    void created_at_index_supports_the_sse_stream_poll() {
        String def = indexDef("idx_event_outbox_created_at");
        assertThat(def).isNotNull();
        assertThat(def).contains("created_at");
    }

    @Test
    void tenant_id_index_supports_the_inbox_tail() {
        String def = indexDef("idx_event_outbox_tenant_id");
        assertThat(def).isNotNull();
        assertThat(def).contains("tenant_id").contains("id DESC");
    }

    private String indexDef(String indexName) {
        String sql = "SELECT indexdef FROM pg_indexes WHERE schemaname='core' "
            + "AND tablename='event_outbox' AND indexname='" + indexName + "'";
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
