package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1833: a Process on a {@code match:} Entity fires on a change in one of the Entity's source tables, and
 * is evaluated on the FUSED row — columns that no single source holds. The Entity view is
 * {@code security_invoker} and carries no {@code tenant_id}, so the fused row is read under the event's tenant
 * (RLS-enforced profile): another tenant's rows must never be fused into it.
 *
 * <p>Fixture: customers fused from an ERP table (the credit limit) and a CRM table (the segment). The rule
 * {@code credit_limit>100} AND {@code segment=GOLD} is true of no single table.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ProcessOnEntityIT {

    private static final String TYPE = "decision.poe-gold";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String ENTITY = """
        kind: Entity
        name: PoeCustomer
        match: [code]
        scopes: [poe]
        sources:
          - table: t_poe_erp
            map: { code: code, credit_limit: credit_limit }
          - table: t_poe_crm
            map: { customer: code, segment: segment }
        """;

    private static final String PROCESS = """
        apiVersion: apps.lumnik.io/v1
        kind: Process
        metadata: { name: poe-gold }
        spec:
          scope: poe
          on: { event: data.row.changed, table: connector.v_poecustomer }
          when: [ "credit_limit>100", "segment=GOLD" ]
          emit: { type: decision.poe-gold, message: "{code} is GOLD over its limit", key: code }
        """;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM core.process_manifest WHERE name = 'poe-gold'");
        exec("DELETE FROM core.event_outbox WHERE source = 'it-poe' OR event_type = '" + TYPE + "'");
        exec("DELETE FROM connector.dynamic_table WHERE mapping_id IN ('poe-erp', 'poe-crm')");
        exec("DROP VIEW IF EXISTS connector.\"v_poecustomer\"");
        exec("DROP TABLE IF EXISTS connector.t_poe_erp");
        exec("DROP TABLE IF EXISTS connector.t_poe_crm");
        exec("CREATE TABLE connector.t_poe_erp (tenant_id bigint NOT NULL, _row_hash text, code text, credit_limit text)");
        exec("CREATE TABLE connector.t_poe_crm (tenant_id bigint NOT NULL, _row_hash text, customer text, segment text)");
        // No FORCE: the owner (admin, BYPASSRLS in production) reads every tenant's rows, as the consumer's own reads do;
        // lumnik_app and lumnik_readonly are not owners, so the policy binds them.
        exec("INSERT INTO connector.t_poe_erp VALUES "
           + "(1, 'e1-c1', 'C-1', '500'), (1, 'e1-c2', 'C-2', '500'), (1, 'e1-c3', 'C-3', '500'), (1, 'e1-null', NULL, '500'),"
           + "(1, 'e1-c9', 'C-9', '500'), (2, 'e2-c9', 'C-9', '500')");
        exec("INSERT INTO connector.t_poe_crm VALUES "
           + "(1, 'm1-c1', 'C-1', 'GOLD'), (1, 'm1-c2', 'C-2', 'SILVER'), (2, 'm2-c9', 'C-9', 'GOLD')");
        for (String t : new String[] {"t_poe_erp", "t_poe_crm"}) {
            exec("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
            exec("CREATE POLICY tenant_isolation ON connector." + t
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            exec("GRANT SELECT ON connector." + t + " TO lumnik_app, lumnik_readonly");
            for (int tenant : new int[] {1, 2}) {
                exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                   + "VALUES (" + tenant + ", '" + t.replace("t_", "").replace('_', '-') + "', 'connector', '" + t + "', '{}'::jsonb)");
            }
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply").then().statusCode(200);
        for (int tenant : new int[] {1, 2}) {
            exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES ("
               + tenant + ", 'poe-gold', '" + PROCESS.replace("'", "''") + "', 'poe', 'connector.v_poecustomer', '" + TYPE + "')");
        }
    }

    private void change(int tenant, String sourceTable, String hash) {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (" + tenant + ", 'data.row.changed', 'it-poe', 'connector." + sourceTable + "', '" + hash + "', "
           + "'{\"op\":\"insert\"}'::jsonb, NOW(), NOW())");
    }

    /** The consumer's cursor has swept every event inserted so far. */
    private void awaitSwept() {
        long max = scalar("SELECT COALESCE(MAX(id), 0) FROM core.event_outbox");
        await().atMost(25, SECONDS).untilAsserted(() ->
            assertThat(scalar("SELECT COALESCE(MAX(last_event_id), 0) FROM core.event_consumer WHERE consumer_name = 'process'"))
                .isGreaterThanOrEqualTo(max));
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private record Decision(String entity, String entityKey, String message) {}

    private List<Decision> decisions(int tenant) {
        List<Decision> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT entity, entity_key, payload::text FROM core.event_outbox "
                 + "WHERE tenant_id = ? AND event_type = ? ORDER BY id")) {
            ps.setInt(1, tenant);
            ps.setString(2, TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonNode n = new ObjectMapper().readTree(rs.getString(3));
                    out.add(new Decision(rs.getString(1), rs.getString(2), n.get("message").asText()));
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    @Test
    void aSourceChangeIsEvaluatedOnTheFusedRow() {
        change(1, "t_poe_erp", "e1-c1");   // GOLD in the CRM, over the limit in the ERP: true of the fused row only
        change(1, "t_poe_erp", "e1-c2");   // SILVER
        change(1, "t_poe_erp", "e1-c3");   // no CRM row at all: no segment
        awaitSwept();

        assertThat(decisions(1)).containsExactly(
            new Decision("connector.v_poecustomer", "C-1", "C-1 is GOLD over its limit"));
    }

    @Test
    void aChangeInTheOtherSourceIsEvaluatedOnTheSameFusedRow() {
        change(1, "t_poe_crm", "m1-c1");
        awaitSwept();

        assertThat(decisions(1)).extracting(Decision::entityKey).containsExactly("C-1");
    }

    /** The view is security_invoker with no tenant_id: read as admin, tenant 2's CRM row would fuse in. */
    @Test
    void anotherTenantsRowWithTheSameKeyIsNeverFusedIn() {
        change(1, "t_poe_erp", "e1-c9");   // tenant 1 holds C-9 over its limit, with NO CRM row of its own
        change(2, "t_poe_erp", "e2-c9");   // control: tenant 2 holds the whole picture
        awaitSwept();

        assertThat(decisions(1)).as("tenant 1 must not see tenant 2's GOLD segment").isEmpty();
        assertThat(decisions(2)).extracting(Decision::entityKey).containsExactly("C-9");
    }

    @Test
    void aReplayDoesNotDuplicateTheDecision() {
        change(1, "t_poe_erp", "e1-c1");
        awaitSwept();
        assertThat(decisions(1)).hasSize(1);

        exec("UPDATE core.event_consumer SET last_xact_id = 0, last_event_id = 0 WHERE consumer_name = 'process'");
        awaitSwept();
        assertThat(decisions(1)).hasSize(1);
    }

    /** One decision per source event: a physical change that still satisfies the rule decides again. */
    @Test
    void aNewEventForTheSameRowDecidesAgain() {
        change(1, "t_poe_erp", "e1-c1");
        awaitSwept();
        change(1, "t_poe_erp", "e1-c1");
        awaitSwept();

        assertThat(decisions(1)).extracting(Decision::entityKey).containsExactly("C-1", "C-1");
    }

    @Test
    void aSourceRowWithoutAMatchKeyBelongsToNoFusedRow() {
        change(1, "t_poe_erp", "e1-null");
        change(1, "t_poe_erp", "e1-c1");   // the event after it is still served
        awaitSwept();

        assertThat(decisions(1)).extracting(Decision::entityKey).containsExactly("C-1");
    }
}
