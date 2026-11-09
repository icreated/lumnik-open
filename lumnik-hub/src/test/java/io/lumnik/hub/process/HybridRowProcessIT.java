package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter;
import io.lumnik.hub.event.RowEventGate;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedRestConnector;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Process on an {@code ext.*} table fires for the rows a REST connector's hybrid writer adds or changes (#1816).
 * Each test follows the documented limit: the table is created by a first write with no subscription, the Process
 * is applied through its door, and only then are the observed rows written. The scheduler is paused: each test
 * sweeps the "process" consumer itself.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class HybridRowProcessIT {

    private static final long TENANT = 18163L;
    private static final long OTHER_TENANT = 18164L;
    private static final String SCOPE = "lab";
    private static final String DECISION = "decision.high-result";
    private static final UUID CONNECTOR = UUID.fromString("00000000-0000-0000-0000-000000181663");
    private static final UUID OTHER_CONNECTOR = UUID.fromString("00000000-0000-0000-0000-000000181664");
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject HybridJsonbWriter writer;
    @Inject OutboxSweeper sweeper;
    @Inject ProcessConsumer consumer;
    @Inject SchemaCardStore cards;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private String table;

    @BeforeEach
    void freshTable() {
        String tenants = "(" + TENANT + ", " + OTHER_TENANT + ")";
        execSql(adminDs, "DELETE FROM core.process_manifest WHERE tenant_id IN " + tenants);
        execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE tenant_id IN " + tenants);
        execSql(adminDs, "DELETE FROM connector.connector WHERE tenant_id IN " + tenants);
        // The registry row a write leaves names its connector, so the connector must exist.
        seedRestConnector(adminDs, CONNECTOR, TENANT);
        seedRestConnector(adminDs, OTHER_CONNECTOR, OTHER_TENANT);
        table = "ext.hrp_" + System.nanoTime();
    }

    @Test
    void processFiresForAChangedRowAndForANewRow() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT);

        write(TENANT, sample("S-1", 2400, "s1-v2"), sample("S-2", 150, "s2-v1"));
        sweep();

        Map<String, JsonNode> byKey = decisions(TENANT);
        assertThat(byKey).containsOnlyKeys("s1-v2", "s2-v1");
        // One template, so one sentence shape: only the row's own values differ, never how it got there.
        assertThat(byKey.get("s1-v2").get("message").asText()).isEqualTo("Sample S-1 in G1: 2400 CFU/plate, to_be_verified");
        assertThat(byKey.get("s2-v1").get("message").asText()).isEqualTo("Sample S-2 in G1: 150 CFU/plate, to_be_verified");
        for (JsonNode d : byKey.values()) {
            // jsonb keeps no key order
            assertThat(fieldNames(d.get("fields")))
                .containsExactlyInAnyOrder("sample", "grp", "result", "unit", "review_state");
            assertThat(d.toString()).doesNotContain("\"op\"").doesNotContain("insert").doesNotContain("update");
        }
        assertThat(byKey.get("s1-v2").get("fields").get("result").decimalValue()).isEqualByComparingTo("2400");
    }

    @Test
    void noDecisionAtOrBelowTheThreshold() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT);

        write(TENANT, sample("S-1", 100, "s1-v2"), sample("S-2", 101, "s2-v1"));
        sweep();

        assertThat(rowEvents(TENANT)).as("both rows are events").isEqualTo(2);
        assertThat(decisions(TENANT)).containsOnlyKeys("s2-v1");
    }

    @Test
    void unchangedReingestRaisesNothing() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT);
        write(TENANT, sample("S-1", 2400, "s1-v2"), sample("S-2", 150, "s2-v1"));
        sweep();
        assertThat(decisions(TENANT)).hasSize(2);

        write(TENANT, sample("S-1", 2400, "s1-v2"), sample("S-2", 150, "s2-v1"));
        sweep();

        assertThat(rowEvents(TENANT)).isEqualTo(2);
        assertThat(decisions(TENANT)).hasSize(2);
    }

    @Test
    void rowsOlderThanTheProcessAreNotEvaluated() {
        // S-0 is above the threshold, but it was written before the Process existed.
        createTable(TENANT, sample("S-1", 50, "s1-v1"), sample("S-0", 3000, "s0-v1"));
        applyProcess(TENANT);
        sweep();
        assertThat(decisions(TENANT)).isEmpty();

        write(TENANT, sample("S-1", 50, "s1-v1"), sample("S-0", 3000, "s0-v1"), sample("S-2", 150, "s2-v1"));
        sweep();

        assertThat(decisions(TENANT)).containsOnlyKeys("s2-v1");
    }

    @Test
    void anotherTenantsProcessDoesNotFire() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        createTable(OTHER_TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT);
        applyProcess(OTHER_TENANT);

        // The two rows share a hash on purpose: only the tenant filter of the read tells them apart.
        write(TENANT, sample("S-1", 2400, "shared-v2"));
        write(OTHER_TENANT, sample("S-1", 60, "shared-v2"));
        sweep();

        assertThat(rowEvents(OTHER_TENANT)).as("the other tenant's row is an event too").isEqualTo(1);
        assertThat(decisions(TENANT)).containsOnlyKeys("shared-v2");
        assertThat(decisions(OTHER_TENANT)).isEmpty();
    }

    @Test
    void aRowChangedAgainBeforeTheSweepIsReadOnce() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT);

        write(TENANT, sample("S-1", 2000, "s1-v2"));
        write(TENANT, sample("S-1", 2400, "s1-v3"));
        sweep();

        assertThat(rowEvents(TENANT)).isEqualTo(2);
        // The first event finds no row by its hash any more: the later one covers it.
        Map<String, JsonNode> byKey = decisions(TENANT);
        assertThat(byKey).containsOnlyKeys("s1-v3");
        assertThat(byKey.get("s1-v3").get("message").asText()).isEqualTo("Sample S-1 in G1: 2400 CFU/plate, to_be_verified");
    }

    @Test
    void twoRowsWithTheSameHashAreEachReadByTheirOwnId() {
        createTable(TENANT, sample("S-0", 50, "s0-v1"));
        applyProcess(TENANT);

        // One hash for two rows on purpose: only the row's own id tells them apart (#1816).
        write(TENANT, sample("S-1", 150, "same"), sample("S-2", 200, "same"));
        sweep();

        assertThat(messages(TENANT)).containsExactlyInAnyOrder(
            "Sample S-1 in G1: 150 CFU/plate, to_be_verified", "Sample S-2 in G1: 200 CFU/plate, to_be_verified");
    }

    @Test
    void theDeclaredKeyColumnKeysTheDecision() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT, "key: sample");

        write(TENANT, sample("S-1", 2400, "s1-v2"));
        sweep();

        Map<String, JsonNode> byKey = decisions(TENANT);
        assertThat(byKey).containsOnlyKeys("S-1");   // the sample, not the row hash (#1823)
        assertThat(byKey.get("S-1").get("entity_key").asText()).isEqualTo("S-1");
    }

    @Test
    void aKeyLongerThanTheOutboxColumnStillRaisesItsDecision() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        applyProcess(TENANT, "key: sample");

        String longSample = "S-" + "9".repeat(300);
        write(TENANT, sample(longSample, 2400, "long-v1"));
        sweep();

        Map<String, JsonNode> byKey = decisions(TENANT);
        assertThat(byKey).hasSize(1);
        String key = byKey.keySet().iterator().next();
        assertThat(key).startsWith("sha256:");   // VARCHAR(200): a digest, never a lost decision
        assertThat(byKey.get(key).get("entity_key").asText()).isEqualTo(longSample);   // the payload keeps it whole
    }

    @Test
    void aKeyThatIsNotAColumnIsRefusedAtApply() {
        createTable(TENANT, sample("S-1", 50, "s1-v1"));
        Response r = postProcess(TENANT, "key: sample_id");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body().asString()).contains("spec.emit.key names sample_id — not a column of " + table);
    }

    /**
     * The consumer's real read of a hybrid row: by an index, never a scan of the table (#1816). The primary key and the
     * citation's {@code (tenant_id, row_hash)} index (#1873) both serve it, and the planner may take either.
     */
    @Test
    void theHybridReadUsesAnIndex() {
        // One tenant's rows, analysed: with no statistics the planner ties the primary key with the unique
        // (tenant_id, external_id) index, both estimated at one row. With them, tenant_id selects every row.
        Sample[] samples = new Sample[200];
        for (int i = 0; i < samples.length; i++) samples[i] = sample("S-" + i, 50, "s" + i + "-v1");
        createTable(TENANT, samples);
        execSql(adminDs, "ANALYZE " + table);
        String bare = table.substring(table.indexOf('.') + 1);

        List<String> plan = Jdbc.transaction(adminDs, "plan", c -> {
            Jdbc.update(c, "plan", "SET LOCAL enable_seqscan = off", ps -> {});
            return Jdbc.query(c, "plan", "EXPLAIN " + ProcessConsumer.fetchSql(table, "row_hash"), ps -> {
                ps.setLong(1, 1L);
                ps.setLong(2, TENANT);
                ps.setString(3, "s1-v1");
            }, rs -> rs.getString(1));
        });

        // Keyed by the row's id whichever index carries it: a read that dropped `id = ?` would answer "the oldest row of
        // this hash", not the row asked for, and no longer show the predicate in its plan.
        assertThat(String.join("\n", plan)).containsPattern("Index Scan using (" + bare + "_pkey|idx_" + bare + "_row_hash)")
            .contains("id = '1'::bigint").doesNotContain("Seq Scan");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private record Sample(Map<String, Object> item, String hash) {}

    private static Sample sample(String id, int result, String hash) {
        return new Sample(Map.of("sample", id, "grp", "G1", "result", result, "unit", "CFU/plate",
            "review_state", "to_be_verified"), hash);
    }

    private HybridJsonbSpec spec() {
        return new HybridJsonbSpec(table, "$.sample", List.of(
            new PromotedColumn("sample", "$.sample", SqlType.TEXT, Optional.empty()),
            new PromotedColumn("grp", "$.grp", SqlType.TEXT, Optional.empty()),
            new PromotedColumn("result", "$.result", SqlType.DECIMAL, Optional.empty()),
            new PromotedColumn("unit", "$.unit", SqlType.TEXT, Optional.empty()),
            new PromotedColumn("review_state", "$.review_state", SqlType.TEXT, Optional.empty())));
    }

    /** The first write: it creates and registers the table, and no subscription exists yet. */
    private void createTable(long tenant, Sample... samples) {
        write(tenant, samples);
        assertThat(rowEvents(tenant)).as("no subscription yet: no event").isZero();
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

    /** Through the real door: the table must be in the scope's card and registered by a write. */
    private void applyProcess(long tenant) {
        applyProcess(tenant, "");
    }

    /** @param emitLine one more line under {@code emit:}, or empty */
    private void applyProcess(long tenant, String emitLine) {
        Response r = postProcess(tenant, emitLine);
        assertThat(r.statusCode()).as(r.body().asString()).isEqualTo(200);
    }

    private Response postProcess(long tenant, String emitLine) {
        List<SchemaCard.Column> cols = List.of(
            new SchemaCard.Column("sample", "text", "Sample"),
            new SchemaCard.Column("grp", "text", "Group"),
            new SchemaCard.Column("result", "numeric", "Result"),
            new SchemaCard.Column("unit", "text", "Unit"),
            new SchemaCard.Column("review_state", "text", "Review state"));
        cards.save(tenant, "registry", new SchemaCard(SCOPE, List.of(new SchemaCard.Table(table, "Lab", cols))));
        String manifest = """
            apiVersion: apps.lumnik.io/v1
            kind: Process
            metadata: { name: high-result }
            spec:
              scope: lab
              on: { event: data.row.changed, table: %s }
              when: [ "result>100" ]
              emit:
                type: decision.high-result
                message: "Sample {sample} in {grp}: {result} {unit}, {review_state}"
                %s
            """.formatted(table, emitLine);
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", String.valueOf(tenant))
            .contentType("application/yaml").body(manifest)
        .when().post("/api/processes/apply");
    }

    /** Batch after batch until the consumer has read everything decided. */
    private void sweep() {
        TenantContext.runAsSystem(() -> {
            while (sweeper.sweep(ProcessConsumer.CONSUMER_NAME, 500, consumer.batchProcessor()) == 500) {
                // the next batch
            }
        });
    }

    private long rowEvents(long tenant) {
        return Jdbc.queryOne(adminDs, "rowEvents", "SELECT count(*) FROM core.event_outbox "
            + "WHERE tenant_id = ? AND event_type = ? AND entity = ?", ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, RowEventGate.DATA_ROW_CHANGED);
                ps.setString(3, table);
            }, rs -> rs.getLong(1)).orElseThrow();
    }

    /** This tenant's decisions on the test's table, by their key: the row hash, or the declared column's value. */
    private Map<String, JsonNode> decisions(long tenant) {
        Map<String, JsonNode> out = new HashMap<>();
        Jdbc.query(adminDs, "decisions", "SELECT entity_key, payload::text FROM core.event_outbox "
            + "WHERE tenant_id = ? AND event_type = ? AND entity = ?", ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, DECISION);
                ps.setString(3, table);
            }, rs -> {
                if (out.put(rs.getString(1), JSON.readTree(rs.getString(2))) != null) {
                    throw new AssertionError("two decisions for row " + rs.getString(1));
                }
                return null;
            });
        return out;
    }

    /** This tenant's decision messages on the test's table. */
    private List<String> messages(long tenant) {
        return Jdbc.query(adminDs, "messages", "SELECT payload->>'message' FROM core.event_outbox "
            + "WHERE tenant_id = ? AND event_type = ? AND entity = ?", ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, DECISION);
                ps.setString(3, table);
            }, rs -> rs.getString(1));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
