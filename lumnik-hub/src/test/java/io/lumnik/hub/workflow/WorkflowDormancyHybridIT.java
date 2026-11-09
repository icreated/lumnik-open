package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedRestConnector;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A Workflow watches a REST connector's hybrid {@code ext.*} table (#1816). The table is made by the hybrid writer, as
 * a run makes it, the workflow goes through its apply door, and each test drives the sweeps itself. RLS is enforced:
 * the door and the due sweep read as the readonly role, under the tenant.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowDormancyHybridIT {

    private static final long TENANT = 18165L;
    private static final long OTHER_TENANT = 18166L;
    private static final UUID CONNECTOR = UUID.fromString("00000000-0000-0000-0000-000000181665");
    private static final UUID OTHER_CONNECTOR = UUID.fromString("00000000-0000-0000-0000-000000181666");
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final String WF = "wf-hybrid-it";
    /** The due sweep's clock, fixed: the due dates below are read against it. */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    /** Three days before {@link #NOW}. */
    private static final String OVERDUE = "2026-10-17";

    @Inject HybridJsonbWriter writer;
    @Inject WorkflowDormancySweep dormancy;
    @Inject WorkflowDueSweep due;
    @Inject WorkflowObservationStore observations;
    @Inject SchemaCardStore cards;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private String table;

    @BeforeEach
    void freshTable() {
        String tenants = "(" + TENANT + ", " + OTHER_TENANT + ")";
        for (String t : List.of("core.workflow_manifest", "core.workflow_observation", "core.workflow_transition",
                "core.workflow_due_alert", "core.event_outbox", "connector.dynamic_table", "connector.connector"))
            execSql(adminDs, "DELETE FROM " + t + " WHERE tenant_id IN " + tenants);
        // The registry row a write leaves names its connector, so the connector must exist.
        seedRestConnector(adminDs, CONNECTOR, TENANT);
        seedRestConnector(adminDs, OTHER_CONNECTOR, OTHER_TENANT);
        table = "ext.wfh_" + System.nanoTime();
    }

    @Test
    void aDocumentSleepingInAnExtTableIsObservedThenAlerted() {
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"));
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v2"), sample("S-2", "verified", OVERDUE, "s2-v1"));
        write(OTHER_TENANT, sample("S-9", "to_be_verified", OVERDUE, "s9-v1"));
        apply("external_id", "dormancy: { after: 1m }");

        dormancy.sweepNow();
        assertThat(observations.loadAll(TENANT, WF)).as("the other tenant's row is not this tenant's document")
            .containsOnlyKeys("S-1", "S-2");
        assertThat(decisions("decision.dormant.")).as("cold start: nothing has slept yet").isEmpty();

        age();
        dormancy.sweepNow();
        assertThat(decisions("decision.dormant.")).as("S-2 is terminal").containsExactly("S-1");
    }

    @Test
    void theKeyMayBeAPromotedColumn() {
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"));
        apply("sample", "dormancy: { after: 1m }");

        dormancy.sweepNow();
        age();
        dormancy.sweepNow();
        assertThat(decisions("decision.dormant.")).containsExactly("S-1");
    }

    @Test
    void aDueDateInAnExtTableIsReadUnderTheTenant() {
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"),
            sample("S-2", "to_be_verified", "2026-10-25", "s2-v1"));
        write(OTHER_TENANT, sample("S-9", "to_be_verified", OVERDUE, "s9-v1"));
        apply("external_id", "due: { column: due_on, states: [to_be_verified] }");

        due.sweepAt(NOW);
        assertThat(decisions("decision.overdue.")).as("S-2 is not due yet; S-9 is the other tenant's")
            .containsExactly("S-1");
    }

    /**
     * #1820: a hybrid target registered under {@code connector} has {@code indexed_at} and no {@code _ingested_at}, as
     * an {@code ext} one: the column is told by the registry's marker, not by the schema prefix.
     */
    @Test
    void aHybridTableUnderConnectorIsSweptLikeAnExtOne() {
        table = "connector.t_wfh_" + System.nanoTime();
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"), sample("S-2", "verified", OVERDUE, "s2-v1"));
        apply("external_id", "dormancy: { after: 1m }");

        dormancy.sweepNow();
        age();
        dormancy.sweepNow();
        assertThat(decisions("decision.dormant.")).as("S-2 is terminal").containsExactly("S-1");
    }

    @Test
    void aDueDateInAHybridTableUnderConnectorIsReadByTheSameColumn() {
        table = "connector.t_wfh_" + System.nanoTime();
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"), sample("S-2", "to_be_verified", "2026-10-25", "s2-v1"));
        apply("external_id", "due: { column: due_on, states: [to_be_verified] }");

        due.sweepAt(NOW);
        assertThat(decisions("decision.overdue.")).containsExactly("S-1");
    }

    /** #1820: the ingestion check follows the tables a run reports as written; a hybrid table is never reported. */
    @Test
    void anIngestionCheckOnAHybridTableUnderConnectorIsRefused() {
        table = "connector.t_wfh_" + System.nanoTime();
        write(TENANT, sample("S-1", "to_be_verified", OVERDUE, "s1-v1"));
        cards.save(TENANT, "registry", new SchemaCard("lab", List.of()));

        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(TENANT))
            .contentType("application/yaml").body(manifest("external_id", "check: { trigger: ingestion }"))
        .when().post("/api/workflows/apply")
        .then().statusCode(400)
            .body("error", org.hamcrest.Matchers.containsString("cannot watch " + table));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private record Sample(Map<String, Object> item, String hash) {}

    private static Sample sample(String id, String state, String dueOn, String hash) {
        return new Sample(Map.of("sample", id, "review_state", state, "due_on", dueOn), hash);
    }

    private HybridJsonbSpec spec() {
        return new HybridJsonbSpec(table, "$.sample", List.of(
            new PromotedColumn("sample", "$.sample", SqlType.TEXT, Optional.empty()),
            new PromotedColumn("review_state", "$.review_state", SqlType.TEXT, Optional.empty()),
            new PromotedColumn("due_on", "$.due_on", SqlType.DATE, Optional.empty())));
    }

    private void write(long tenant, Sample... samples) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> sys = new ArrayList<>();
        for (Sample s : samples) {
            items.add(s.item());
            sys.add(Map.of("_row_hash", s.hash()));
        }
        UUID connector = tenant == TENANT ? CONNECTOR : OTHER_CONNECTOR;
        runInTxAsSystem(utx, () -> writer.write(spec(), items, sys, tenant, connector, ENDPOINT));
    }

    private String manifest(String key, String watched) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: %s }
            spec:
              scope: lab
              provenance: declared
              on: { table: %s, column: review_state, key: %s }
              %s
              states:
                - { code: to_be_verified, initial: true }
                - { code: verified, terminal: true }
              transitions:
                - { from: [to_be_verified], to: verified }
            """.formatted(WF, table, key, watched);
    }

    /** Through the door, as the readonly role sees the table: it is there, and so is its key. */
    private void apply(String key, String watched) {
        cards.save(TENANT, "registry", new SchemaCard("lab", List.of()));
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(TENANT))
            .contentType("application/yaml").body(manifest(key, watched))
        .when().post("/api/workflows/apply")
        .then().statusCode(200)
            .body("dataTruth.tableMissing", equalTo(false))
            .body("dataTruth.keyColumnMissing", equalTo(false))
            .body("dataTruth.checkUnavailable", equalTo(false));
    }

    private void age() {
        execSql(adminDs, "UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 minutes' "
            + "WHERE tenant_id = " + TENANT);
    }

    /** The keys this tenant's workflow raised a decision of this kind for, in order. */
    private List<String> decisions(String kind) {
        return Jdbc.query(adminDs, "decisions", "SELECT entity_key FROM core.event_outbox "
            + "WHERE tenant_id = ? AND event_type = ? ORDER BY id", ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, kind + WF);
            }, rs -> rs.getString(1));
    }
}
