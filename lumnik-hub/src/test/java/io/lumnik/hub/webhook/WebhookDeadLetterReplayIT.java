package io.lumnik.hub.webhook;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * #1837: an event the webhook consumer failed on before its deliveries existed is replayed by recording them: one
 * delivery per live endpoint, none for an endpoint that was never meant to get the event.
 */
// The schedulers are off: the live webhook consumer would sweep the event itself and record its own delivery.
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookDeadLetterReplayIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @AfterEach
    void clean() {
        exec("DELETE FROM core.webhook_delivery WHERE tenant_id = 4304");
        exec("DELETE FROM core.webhook_endpoint WHERE tenant_id = 4304");
        exec("DELETE FROM core.event_dead_letter WHERE tenant_id = 4304");
        exec("DELETE FROM core.event_outbox WHERE tenant_id = 4304");
    }

    @Test
    void theReplayRecordsTheDeliveriesOfTheLiveEndpointsOnce() {
        exec("INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled) VALUES "
           + "(4304, 'dl-live', 'http://localhost:1/none', 'generic', 'NONE', '{dlw.test.*}', TRUE), "
           + "(4304, 'dl-other', 'http://localhost:1/none', 'generic', 'NONE', '{other.*}', TRUE)");
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
           + "VALUES (4304, 'dlw.test.one', 'system', '{\"k\":\"v\"}'::jsonb, NOW(), NOW())");
        exec("INSERT INTO core.event_dead_letter (consumer_name, event_id, tenant_id, error, envelope) "
           + "SELECT 'webhook', id, 4304, 'X', to_jsonb(o) FROM core.event_outbox o WHERE tenant_id = 4304");
        long id = scalar("SELECT id FROM core.event_dead_letter WHERE tenant_id = 4304");

        given().headers(TestIdentity.tenantAndUser(4304)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(200);
        given().headers(TestIdentity.tenantAndUser(4304)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(409);

        assertEquals(1, scalar("SELECT count(*) FROM core.webhook_delivery WHERE tenant_id = 4304 AND event_type = 'dlw.test.one'"),
            "one delivery, for the endpoint that subscribed — and a second replay is refused");
    }

    /** A replay that recorded nothing (no live endpoint wants the event) did not redo the work: the letter stays open. */
    @Test
    void aReplayThatRecordsNothingFailsAndLeavesTheLetterOpen() {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
           + "VALUES (4304, 'dlw.test.none', 'system', '{\"k\":\"v\"}'::jsonb, NOW(), NOW())");
        exec("INSERT INTO core.event_dead_letter (consumer_name, event_id, tenant_id, error, envelope) "
           + "SELECT 'webhook', id, 4304, 'X', to_jsonb(o) FROM core.event_outbox o WHERE tenant_id = 4304");
        long id = scalar("SELECT id FROM core.event_dead_letter WHERE tenant_id = 4304");

        given().headers(TestIdentity.tenantAndUser(4304)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(500);

        assertEquals(1, scalar("SELECT count(*) FROM core.event_dead_letter WHERE tenant_id = 4304 AND replayed_at IS NULL"));
    }
}
