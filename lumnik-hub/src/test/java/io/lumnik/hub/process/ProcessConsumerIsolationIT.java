package io.lumnik.hub.process;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import static io.restassured.RestAssured.given;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.testsupport.YamlBody;
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
 * #1844: the outbox sweeper moves its cursor past an event whatever the consumer threw, so a failure in the part of
 * {@code listening()} that serves an Entity (or in reading one process) used to lose the event for EVERY process of the
 * table, the ordinary ones too. A broken Entity, or a broken process, is skipped — the others are served.
 */
@QuarkusTest
class ProcessConsumerIsolationIT {

    private static final String TYPE = "decision.iso-good";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry entities;

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

    @BeforeEach
    void seed() {
        clean();
        exec("CREATE TABLE connector.t_iso (tenant_id bigint, _row_hash text, code text)");
        exec("INSERT INTO connector.t_iso VALUES (4301, 'h1', 'A')");
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4301, 'iso-good', 'apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: iso-good }\nspec:\n  scope: iso\n"
           + "  on: { event: data.row.changed, table: connector.t_iso }\n  when: [ code=A ]\n"
           + "  emit: { type: " + TYPE + ", message: \"good\" }', 'iso', 'connector.t_iso', '" + TYPE + "')");
    }

    @AfterEach
    void clean() {
        exec("DELETE FROM connector.entity_manifest WHERE name IN ('isobroken', 'isogood')");   // global: never leave them for the others
        exec("DROP VIEW IF EXISTS connector.\"v_isogood\"");
        exec("DELETE FROM connector.dynamic_table WHERE mapping_id = 'iso-src'");
        entities.remove("isogood");
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 4301");
        exec("DELETE FROM core.event_outbox WHERE tenant_id = 4301");
        exec("DROP TABLE IF EXISTS connector.t_iso");
    }

    private void changeAndSweep() {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (4301, 'data.row.changed', 'it-iso', 'connector.t_iso', 'h1', '{\"op\":\"insert\"}'::jsonb, NOW(), NOW())");
        long max = scalar("SELECT MAX(id) FROM core.event_outbox");
        await().atMost(25, SECONDS).untilAsserted(() ->
            assertThat(scalar("SELECT COALESCE(MAX(last_event_id), 0) FROM core.event_consumer WHERE consumer_name = 'process'"))
                .isGreaterThanOrEqualTo(max));
    }

    private long decisions() {
        return scalar("SELECT count(*) FROM core.event_outbox WHERE tenant_id = 4301 AND event_type = '" + TYPE + "'");
    }

    /** A process on the view of an Entity whose stored YAML cannot be read: reading it fails the registry's reload. */
    @Test
    void anUnreadableEntityDoesNotCostTheTableItsOrdinaryProcesses() {
        exec("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES ('isobroken', 'kind: Entity\nname: [not closed')");
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4301, 'iso-on-broken', 'apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: iso-on-broken }\nspec:\n  scope: iso\n"
           + "  on: { event: data.row.changed, table: connector.v_isobroken }\n  when: [ code=A ]\n"
           + "  emit: { type: decision.iso-broken, message: \"x\" }', 'iso', 'connector.v_isobroken', 'decision.iso-broken')");

        changeAndSweep();

        assertThat(decisions()).as("the ordinary process of the table is still served").isEqualTo(1);
    }

    /**
     * Sergey's review of #1847: the registry reloads every stored Entity as ONE read, so a single unreadable row used to
     * fail the lookup of an Entity that is itself fine. A VALID Entity over the same source keeps its process while another
     * stored Entity is unreadable (cold registry: it has to be looked up).
     */
    @Test
    void aValidEntityKeepsItsProcessWhileAnotherStoredEntityIsUnreadable() {
        exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
           + "VALUES (4301, 'iso-src', 'connector', 't_iso', '{}'::jsonb)");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(4301)).contentType("application/yaml")
            .body("kind: Entity\nname: IsoGood\nmatch: [code]\nscopes: [iso]\nsources:\n  - table: t_iso\n    map: { code: code }\n")
            .post("/api/entities/apply").then().statusCode(200);
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4301, 'iso-ent', 'apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: iso-ent }\nspec:\n  scope: iso\n"
           + "  on: { event: data.row.changed, table: connector.v_isogood }\n  when: [ code=A ]\n"
           + "  emit: { type: decision.iso-ent, message: \"ent\" }', 'iso', 'connector.v_isogood', 'decision.iso-ent')");
        exec("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES ('isobroken', 'kind: Entity\nname: [not closed')");
        entities.remove("isogood");   // cold: the Entity has to be found again, through a reload the broken row would fail

        changeAndSweep();

        assertThat(scalar("SELECT count(*) FROM core.event_outbox WHERE tenant_id = 4301 AND event_type = 'decision.iso-ent'"))
            .as("the valid Entity's process still decides").isEqualTo(1);
        assertThat(decisions()).as("and so does the ordinary one").isEqualTo(1);
    }

    /** A stored process whose YAML cannot be read is left out, as one whose {@code when} cannot be read already was. */
    @Test
    void anUnreadableProcessDoesNotCostTheTableItsOtherProcesses() {
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4301, 'iso-bad', 'spec: [not closed', 'iso', 'connector.t_iso', 'decision.iso-bad')");

        changeAndSweep();

        assertThat(decisions()).isEqualTo(1);
    }
}
