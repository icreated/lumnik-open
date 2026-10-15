package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AuditRecorderExplicitIdentityIT {

    @Inject AuditRecorder recorder;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /**
     * Proves the new overload records exactly the tenant/actor it is handed, independent of
     * whatever this test thread's own request-context ThreadLocals hold (there are none here —
     * this test calls the bean directly, off any HTTP request, which is exactly the situation
     * the streaming callback in #677 is in).
     */
    @Test
    void recordsTheExplicitlyPassedTenantAndActorNotAnyThreadLocal() throws Exception {
        String key = "explicit-identity-" + System.nanoTime();

        recorder.record(4242L, 99L, "EntitySnapshotTest", key, "EGRESS",
            Map.of("entity", "Widget", "row_count", 3L, "byte_count", 77L, "sha256", "deadbeef"));

        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT tenant_id, actor_id, operation, changes ->> 'entity' AS entity "
               + "FROM core.audit_outbox WHERE entity_key = '" + key + "'")) {
            assertTrue(rs.next(), "expected one audit_outbox row for " + key);
            assertEquals(4242L, rs.getLong("tenant_id"));
            assertEquals(99L, rs.getLong("actor_id"));
            assertEquals("EGRESS", rs.getString("operation"));
            assertEquals("Widget", rs.getString("entity"));
        }
    }

    /** actorId may be null (no user on the path) — the column must be NULL, not 0. */
    @Test
    void nullActorIdIsStoredAsSqlNull() throws Exception {
        String key = "explicit-identity-null-actor-" + System.nanoTime();

        recorder.record(4242L, null, "EntitySnapshotTest", key, "EGRESS", Map.of("entity", "Widget"));

        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT actor_id FROM core.audit_outbox WHERE entity_key = '" + key + "'")) {
            assertTrue(rs.next());
            rs.getLong("actor_id");
            assertTrue(rs.wasNull(), "actor_id must be SQL NULL, not 0");
        }
    }
}
