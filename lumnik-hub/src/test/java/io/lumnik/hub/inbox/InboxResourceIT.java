package io.lumnik.hub.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class InboxResourceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void insertEvent(long tenant, String type, String source, String payloadJson) {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (" + tenant + ", '" + type + "', '" + source + "', 'connector.t_inbox_demo', 'hash-" + type + "', "
           + "'" + payloadJson.replace("'", "''") + "'::jsonb, NOW(), NOW())");
    }

    @BeforeEach
    void seed() {
        exec("DELETE FROM core.event_outbox WHERE tenant_id IN (5100, 5101)");
        // caller tenant: two decisions (older then newer) + noise (a data event, a run event)
        insertEvent(5100, "decision.lyon-client-arrive", "process:lyon-client-arrive",
            "{\"message\": \"Nouveau client à Lyon\", \"process\": \"lyon-client-arrive\", "
          + "\"entity\": \"connector.t_inbox_demo\", \"entity_key\": \"hash-old\"}");
        insertEvent(5100, "decision.gold-client-arrive", "process:gold-client-arrive",
            "{\"message\": \"Nouveau client GOLD\", \"process\": \"gold-client-arrive\", "
          + "\"entity\": \"connector.t_inbox_demo\", \"entity_key\": \"hash-new\"}");
        insertEvent(5100, "data.row.changed", "ingest", "{\"op\": \"insert\"}");
        insertEvent(5100, "connector.run.completed", "system", "{}");
        // another tenant's decision must NEVER appear
        insertEvent(5101, "decision.other-tenant", "process:other", "{\"message\": \"secret\"}");
    }

    @Test
    void listsOnlyTheCallerTenantsDecisions_newestFirst() {
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox")
        .then().statusCode(200)
            .body("size()", is(2))
            .body("[0].type", is("decision.gold-client-arrive"))   // newest first (higher id)
            .body("[0].process", is("gold-client-arrive"))
            .body("[0].message", is("Nouveau client GOLD"))
            .body("[0].entityKey", is("hash-decision.gold-client-arrive"))
            .body("[1].type", is("decision.lyon-client-arrive"))
            .body("type", not(hasItem("data.row.changed")))
            .body("message", not(hasItem("secret")));
    }

    @Test
    void limitIsRespected() {
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox?limit=1")
        .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].type", is("decision.gold-client-arrive"));
    }

    @Test
    void aNonObjectPayloadDegradesGracefully() {
        insertEvent(5100, "decision.weird", "process:weird", "\"just-a-string\"");
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox?limit=1")
        .then().statusCode(200)
            .body("[0].type", is("decision.weird"))
            .body("[0].process", is("weird"))       // fallback: source minus "process:"
            .body("[0].message", is(""));            // fallback: empty, never a 500
    }

    @Test
    void objectValuedPayloadFieldsDoNotEraseTheFallback() {
        // process/message present but NON-scalar (object / array): must degrade to the
        // source-derived fallback, not be overwritten with empty strings.
        insertEvent(5100, "decision.weird2", "process:weird2",
            "{\"process\": {\"nested\": \"x\"}, \"message\": [\"a\", \"b\"]}");
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox?limit=1")
        .then().statusCode(200)
            .body("[0].type", is("decision.weird2"))
            .body("[0].process", is("weird2"))   // fallback preserved, NOT ""
            .body("[0].message", is(""));          // stays empty, NOT some container stringification
    }

    @Test
    void withoutTenant_401() {
        given()
        .when().get("/api/inbox")
        .then().statusCode(401);
    }

    private long newestDecisionId() {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM core.event_outbox WHERE tenant_id = 5100 "
               + "AND event_type LIKE 'decision.%' ORDER BY id DESC LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long countAcks(long decisionId) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.event_outbox WHERE tenant_id = 5100 "
               + "AND event_type = 'annotation.decision.acked' AND payload->>'decision_id' = ?")) {
            ps.setString(1, String.valueOf(decisionId));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void listCarriesTheDecisionId() {
        long id = newestDecisionId();
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox?limit=1")
        .then().statusCode(200)
            .body("[0].id", is((int) id));   // RestAssured JSON numbers compare as int at this scale
    }

    @Test
    void ackIsIdempotent_andTheEventIsShapedRight() {
        long id = newestDecisionId();
        given().header("X-Tenant-Id", "5100")
        .when().post("/api/inbox/" + id + "/ack")
        .then().statusCode(200).body("acked", is(true));
        // re-ack: same 200, and STILL exactly one ack event (the dedup arm)
        given().header("X-Tenant-Id", "5100")
        .when().post("/api/inbox/" + id + "/ack")
        .then().statusCode(200).body("acked", is(true));
        Assertions.assertThat(countAcks(id)).isEqualTo(1L);
        // shape: entity/key inherited, source user:*, dedup ack:<id>
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT source, entity, entity_key, dedup_key, payload->>'by' FROM core.event_outbox "
               + "WHERE tenant_id = 5100 AND event_type = 'annotation.decision.acked' "
               + "AND payload->>'decision_id' = ?")) {
            ps.setString(1, String.valueOf(id));
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertThat(rs.next()).isTrue();
                Assertions.assertThat(rs.getString(1)).startsWith("user:");
                Assertions.assertThat(rs.getString(2)).isEqualTo("connector.t_inbox_demo");
                Assertions.assertThat(rs.getString(3)).isNotBlank();
                Assertions.assertThat(rs.getString(4)).isEqualTo("ack:" + id);
                Assertions.assertThat(rs.getString(5)).isNotBlank();
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void ackRefusesForeignNonexistentAndNonDecisionIds_404() {
        // another tenant's decision: indistinguishable from nonexistent (no existence leak)
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM core.event_outbox WHERE tenant_id = 5101 "
               + "AND event_type LIKE 'decision.%' LIMIT 1")) {
            rs.next();
            long foreign = rs.getLong(1);
            given().header("X-Tenant-Id", "5100")
            .when().post("/api/inbox/" + foreign + "/ack")
            .then().statusCode(404);
        } catch (Exception e) { throw new RuntimeException(e); }
        // nonexistent
        given().header("X-Tenant-Id", "5100")
        .when().post("/api/inbox/999999999/ack")
        .then().statusCode(404);
        // a non-decision event (data.row.changed from the seed)
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM core.event_outbox WHERE tenant_id = 5100 "
               + "AND event_type = 'data.row.changed' LIMIT 1")) {
            rs.next();
            long nonDecision = rs.getLong(1);
            given().header("X-Tenant-Id", "5100")
            .when().post("/api/inbox/" + nonDecision + "/ack")
            .then().statusCode(404);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void ackWithoutTenant_401() {
        given()
        .when().post("/api/inbox/1/ack")
        .then().statusCode(401);
    }

    @Test
    void theFold_ackedDecisionsLeaveTheDefaultList_andAllShowsThem() {
        long id = newestDecisionId();
        // before: 2 decisions in the default fold
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox")
        .then().statusCode(200).body("size()", is(2));

        given().header("X-Tenant-Id", "5100")
        .when().post("/api/inbox/" + id + "/ack")
        .then().statusCode(200);

        // the fold: the acked one is gone
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox")
        .then().statusCode(200)
            .body("size()", is(1))
            .body("id", not(hasItem((int) id)));

        // --all: both, with the acked marker
        given().header("X-Tenant-Id", "5100")
        .when().get("/api/inbox?all=true")
        .then().statusCode(200)
            .body("size()", is(2))
            .body("find { it.id == " + id + " }.acked", is(true))
            .body("findAll { it.acked == false }.size()", is(1));
    }

    /**
     * #901: the inbox read is two index paths, in both its forms — the walk reads the tenant's
     * decisions only, and the acked probe is one lookup on the key ack() writes. The plan is read
     * on the admin datasource, the one the resource runs on. It is costed from statistics, so
     * {@link #plan} first gives the tenant a history where row events dominate and each decision
     * is acked, and analyzes it; disabling sequential scans, hash joins and merge joins then
     * leaves the index paths only. This proves the paths EXIST: at test volume a scan or a hash
     * anti-join may be cheaper, and which one the planner picks varies with the machine — the
     * nightly picked the hash anti-join (which reads the acks by tenant, not by key) while a
     * laptop picked the per-row probe.
     */
    @ParameterizedTest(name = "all={0}, bound={1}")
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void theInboxReadIsTwoIndexPaths(boolean all, boolean bound) throws Exception {
        // bound: a caller held to their métiers' sources (#1209) walks the same index, one array test a row
        List<JsonNode> plan = plan(all, bound);
        // Only the walk's predicate implies the partial index's (a bitmap scan names it on an alias-less node).
        Assertions.assertThat(plan).as("the walk reads no row event: %s", plan.get(0))
            .anyMatch(n -> "idx_event_outbox_decisions".equals(n.path("Index Name").asText()));
        Assertions.assertThat(plan).as("the acked probe is one uq_event_outbox_dedup lookup: %s", plan.get(0))
            .anyMatch(n -> "a".equals(n.path("Alias").asText())
                && "uq_event_outbox_dedup".equals(n.path("Index Name").asText())
                && n.path("Index Cond").asText().contains("dedup_key"));
    }

    /**
     * Every node of {@code EXPLAIN (FORMAT JSON)} of the inbox read for tenant 5100, planned over
     * a seeded history that is rolled back with the plan's transaction.
     */
    private List<JsonNode> plan(boolean all, boolean bound) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement();
                 PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + InboxResource.listSql(all))) {
                st.execute("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
                    + "SELECT 5100, CASE WHEN g % 10 = 0 THEN 'decision.history' ELSE 'data.row.changed' END, "
                    // half the decisions come from a source the bound reader holds, so its plan
                    // is planned over rows its filter keeps, not over none (#1211 review)
                    + "CASE WHEN g % 20 = 0 THEN 'process:p-1' ELSE 'ingest' END, "
                    + "'{}'::jsonb, NOW(), NOW() FROM generate_series(1, 2000) g");
                st.execute("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, dedup_key, occurred_at, created_at) "
                    + "SELECT 5100, 'annotation.decision.acked', 'user:7', jsonb_build_object('decision_id', id), "
                    + "'ack:' || id, NOW(), NOW() FROM core.event_outbox WHERE tenant_id = 5100 AND event_type = 'decision.history'");
                st.execute("ANALYZE core.event_outbox");   // samples this transaction's own rows
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL enable_hashjoin = off");    // a nested loop is the per-row probe on the key
                st.execute("SET LOCAL enable_mergejoin = off");
                ps.setLong(1, 5100);
                if (bound) {
                    Array sources = c.createArrayOf("text", new String[] {"process:p-1", "workflow:w-1"});
                    ps.setArray(2, sources);
                    ps.setArray(3, sources);
                } else {
                    ps.setNull(2, Types.ARRAY);   // an unbound caller: every source
                    ps.setNull(3, Types.ARRAY);
                }
                ps.setInt(4, 50);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    List<JsonNode> nodes = new ArrayList<>();
                    collect(MAPPER.readTree(rs.getString(1)).get(0).path("Plan"), nodes);
                    return nodes;
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        for (JsonNode child : node.path("Plans")) collect(child, out);
    }
}
