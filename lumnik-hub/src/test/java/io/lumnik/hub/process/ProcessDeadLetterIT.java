package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1837: a Process that fails on an event leaves a dead letter of its own ({@code process:<name>}) while the processes
 * around it decide; an integrator lists it and replays it, and the replay redoes that Process alone.
 */
@QuarkusTest
class ProcessDeadLetterIT {

    private static final String GOOD = "decision.dl-good";
    private static final String BAD = "decision.dl-bad";
    /** An event type the outbox's VARCHAR(120) refuses: the emit of this process fails with 22001. */
    private static final String TOO_LONG = "decision.dl-bad-" + "x".repeat(120);

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

    private static String process(String name, String type) {
        return "INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
            + "(4302, '" + name + "', 'apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: " + name + " }\nspec:\n  scope: dl\n"
            + "  on: { event: data.row.changed, table: connector.t_dl }\n  when: [ code=A ]\n"
            + "  emit: { type: " + type + ", message: \"m\" }', 'dl', 'connector.t_dl', '" + type + "')";
    }

    @BeforeEach
    void seed() {
        clean();
        exec("CREATE TABLE connector.t_dl (tenant_id bigint, _row_hash text, code text)");
        exec("INSERT INTO connector.t_dl VALUES (4302, 'h1', 'A')");
        exec(process("dl-good", GOOD));
        exec(process("dl-bad", TOO_LONG));
    }

    @AfterEach
    void clean() {
        exec("DELETE FROM core.event_dead_letter WHERE tenant_id IN (4302, 4303)");
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 4302");
        exec("DELETE FROM core.event_outbox WHERE tenant_id = 4302");
        exec("DROP TABLE IF EXISTS connector.t_dl");
    }

    private void changeAndSweep() {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (4302, 'data.row.changed', 'it-dl', 'connector.t_dl', 'h1', '{\"op\":\"insert\"}'::jsonb, NOW(), NOW())");
        long max = scalar("SELECT MAX(id) FROM core.event_outbox");
        await().atMost(25, SECONDS).untilAsserted(() ->
            assertThat(scalar("SELECT COALESCE(MAX(last_event_id), 0) FROM core.event_consumer WHERE consumer_name = 'process'"))
                .isGreaterThanOrEqualTo(max));
    }

    private long decisions(String type) {
        return scalar("SELECT count(*) FROM core.event_outbox WHERE tenant_id = 4302 AND event_type = '" + type + "'");
    }

    @Test
    void aProcessThatFailsLeavesADeadLetterOfItsOwnWhileTheOthersDecide() {
        changeAndSweep();

        assertThat(decisions(GOOD)).as("the neighbour decided").isEqualTo(1);
        given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters")
            .then().statusCode(200)
            .body("consumer", contains("process"))
            .body("target", contains("process:dl-bad"))
            .body("error", contains("SQLSTATE 22001"))
            .body("eventType", contains("data.row.changed"))
            .body("replayedAt", contains((Object) null));
    }

    @Test
    void theReplayRedoesThatProcessAloneAndClosesTheLetter() {
        changeAndSweep();
        exec(process("dl-bad", BAD).concat(
            " ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, emit_type = EXCLUDED.emit_type"));
        exec("DELETE FROM core.event_outbox WHERE tenant_id = 4302 AND event_type = '" + GOOD + "'");
        int id = given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters")
            .then().statusCode(200).extract().path("[0].id");

        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(200);

        assertThat(decisions(BAD)).as("the failed process decided").isEqualTo(1);
        assertThat(decisions(GOOD)).as("its neighbour was not evaluated again").isZero();
        given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters?open=true")
            .then().statusCode(200).body("$", empty());
        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(409);
    }

    @Test
    void aReplayThatFailsAgainAnswers500AndLeavesTheLetterOpen() {
        changeAndSweep();   // dl-bad still emits a type the outbox refuses
        int id = given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters")
            .then().statusCode(200).extract().path("[0].id");

        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(500).body("error", equalTo("replay failed (SQLSTATE 22001)"));

        given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters?open=true")
            .then().statusCode(200).body("target", contains("process:dl-bad"));
    }

    @Test
    void aReplayOfAProcessThatNoLongerListensIsNotDone() {
        changeAndSweep();
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 4302 AND name = 'dl-bad'");
        int id = given().headers(TestIdentity.tenantAndUser(4302)).get("/api/platform/events/dead-letters")
            .then().statusCode(200).extract().path("[0].id");

        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(500).body("error", equalTo("replay failed (IllegalStateException)"));
    }

    /** A whole-event letter, and no process listens on the table any more: the replay did nothing, the letter stays open. */
    @Test
    void aWholeEventReplayWithNoListenerFailsAndLeavesTheLetterOpen() {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (4302, 'data.row.changed', 'it-dl', 'connector.t_dl', 'h1', '{\"op\":\"insert\"}'::jsonb, NOW(), NOW())");
        exec("INSERT INTO core.event_dead_letter (consumer_name, event_id, tenant_id, error, envelope) "
           + "SELECT 'process', id, 4302, 'X', to_jsonb(o) FROM core.event_outbox o WHERE tenant_id = 4302");
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 4302");
        long id = scalar("SELECT id FROM core.event_dead_letter WHERE tenant_id = 4302");

        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(500).body("error", equalTo("replay failed (IllegalStateException)"));

        assertThat(scalar("SELECT count(*) FROM core.event_dead_letter WHERE tenant_id = 4302 AND replayed_at IS NULL")).isEqualTo(1);
    }

    @Test
    void aConsumerWithoutAReplayIsRefusedAndAnotherTenantSeesNothing() {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
           + "VALUES (4302, 'dl.test', 'system', '{}'::jsonb, NOW(), NOW())");
        exec("INSERT INTO core.event_dead_letter (consumer_name, event_id, tenant_id, error, envelope) "
           + "SELECT 'it-noreplay', id, 4302, 'X', to_jsonb(o) FROM core.event_outbox o WHERE event_type = 'dl.test'");
        long id = scalar("SELECT id FROM core.event_dead_letter WHERE tenant_id = 4302 AND consumer_name = 'it-noreplay'");

        given().headers(TestIdentity.tenantAndUser(4302)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(409).body("error", equalTo("consumer 'it-noreplay' has no replay"));
        given().headers(TestIdentity.tenantAndUser(4303)).get("/api/platform/events/dead-letters")
            .then().statusCode(200).body("$", empty());
        given().headers(TestIdentity.tenantAndUser(4303)).post("/api/platform/events/dead-letters/" + id + "/replay")
            .then().statusCode(404);
    }
}
