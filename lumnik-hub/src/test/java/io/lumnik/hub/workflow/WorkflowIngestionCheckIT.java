package io.lumnik.hub.workflow;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1589 end to end, under enforced RLS: the apply reports EN ATT, a later run brings En-cours, and
 * only En-cours becomes a {@code workflow.undeclared.*} event — once, and for its own tenant only.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowIngestionCheckIT {

    @Inject WorkflowIngestionCheck check;
    @Inject WorkflowManifestStore manifests;
    @Inject SchemaCardStore cards;
    @Inject ObjectMapper json;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String WF = "wf-ingestion-it";
    private static final String TABLE = "connector.t_ingit_orders";
    private static final String EVENT = "workflow.undeclared." + WF;

    private static final String ENTITY = """
        kind: Entity
        name: IngItOrder
        match: [ref]
        scopes: [ingit]
        sources:
          - table: t_ingit_orders
            map: { ref: ref, status: status }
        """;

    private static String workflow(boolean checkOnIngestion) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-ingestion-it }
            spec:
              scope: ingit
              provenance: declared
              on: { table: connector.t_ingit_orders, column: status }
            %s  states:
                - { code: SAISIE, initial: true }
                - { code: EN_COURS, aliases: [En cours, EC] }
                - { code: LIVREE, terminal: true }
              transitions:
                - { from: [SAISIE], to: EN_COURS }
                - { from: [EN_COURS], to: LIVREE }
            """.formatted(checkOnIngestion ? "  check: { trigger: ingestion }\n" : "");
    }

    @BeforeEach
    void seed() throws Exception {
        admin("DROP VIEW IF EXISTS connector.v_ingitorder");
        admin("DROP TABLE IF EXISTS " + TABLE);
        admin("CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, ref text, status text)");
        admin("INSERT INTO " + TABLE + " (tenant_id, status) VALUES "
            + "(1, 'EC'), (1, 'En cours'), (1, 'EN ATT'), (1, 'EN ATT'), (1, 'LIVREE'),"
            + "(2, 'Inconnu')");                                           // the other tenant's
        admin("ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY");
        admin("ALTER TABLE " + TABLE + " FORCE ROW LEVEL SECURITY");
        admin("CREATE POLICY tenant_isolation ON " + TABLE
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        admin("GRANT SELECT ON " + TABLE + " TO lumnik_app");
        admin("DELETE FROM core.workflow_undeclared_value WHERE workflow_name = '" + WF + "'");
        admin("DELETE FROM core.event_outbox WHERE event_type = '" + EVENT + "'");
        admin("DELETE FROM core.workflow_manifest WHERE name = '" + WF + "'");
        cards.save(1L, "registry", new SchemaCard("ingit", List.of()));
    }

    /** The apply door, as the integrator calls it: its report is the memory the next run starts from. */
    private JsonNode apply(String yaml) throws Exception {
        String body = given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
            .post("/api/workflows/apply").then().statusCode(200).extract().asString();
        return json.readTree(body);
    }

    private void runEnded(long tenant, Set<String> tablesWritten) {
        check.checkAfter(new ConnectorRunCompleted(UUID.randomUUID(), tenant, UUID.randomUUID(),
            Instant.now(), tablesWritten));
    }

    @Test
    void a_new_spelling_is_said_once_and_what_the_apply_reported_is_not_said_again() throws Exception {
        JsonNode applied = apply(workflow(true));
        assertThat(applied.at("/dataTruth/undeclared/0/value").asText()).isEqualTo("EN ATT");

        runEnded(1, Set.of(TABLE));
        assertThat(events(1)).as("EN ATT was in the apply's report").isEmpty();

        asTenant(1, "INSERT INTO " + TABLE + " (tenant_id, status) VALUES (1, 'En-cours'), (1, 'En-cours')");
        runEnded(1, Set.of(TABLE));
        List<JsonNode> events = events(1);
        assertThat(events).hasSize(1);
        JsonNode p = events.get(0);
        assertThat(p.get("values")).as("tenant 2's Inconnu is not read for tenant 1").hasSize(1);
        assertThat(p.at("/values/0/value").asText()).isEqualTo("En-cours");
        assertThat(p.at("/values/0/rows").asLong()).isEqualTo(2);
        assertThat(p.get("undeclared").asInt()).isEqualTo(2);
        assertThat(p.get("message").asText())
            .isEqualTo("1 new value of connector.t_ingit_orders.status matches no state of workflow "
                + "wf-ingestion-it — declare it as an alias, or correct the source")
            .doesNotContain("En-cours");

        runEnded(1, Set.of(TABLE));
        assertThat(events(1)).as("a value already said is not news").hasSize(1);
    }

    @Test
    void a_reapply_that_declares_the_value_forgets_it() throws Exception {
        apply(workflow(true));
        assertThat(remembered()).containsExactly("EN ATT");
        apply(workflow(true).replace("terminal: true }", "terminal: true }\n    - { code: ATTENTE, aliases: [EN ATT] }")
            .replace("- { from: [EN_COURS], to: LIVREE }", "- { from: [EN_COURS], to: LIVREE }\n    - { from: [SAISIE], to: ATTENTE }"));
        assertThat(remembered()).as("EN ATT is declared now").isEmpty();
        apply(workflow(true));
        assertThat(remembered()).as("undeclared again, and reported again by the apply").containsExactly("EN ATT");
        runEnded(1, Set.of(TABLE));
        assertThat(events(1)).isEmpty();
    }

    @Test
    void nothing_is_checked_unless_declared_or_unless_the_run_wrote_the_table() throws Exception {
        apply(workflow(false));
        asTenant(1, "INSERT INTO " + TABLE + " (tenant_id, status) VALUES (1, 'En-cours')");
        runEnded(1, Set.of(TABLE));
        assertThat(events(1)).as("spec.check absent").isEmpty();

        apply(workflow(true).replace("[En cours, EC]", "[En cours, EC, En-cours]"));
        asTenant(1, "INSERT INTO " + TABLE + " (tenant_id, status) VALUES (1, 'Encours')");
        runEnded(1, Set.of("connector.t_ingit_other"));
        assertThat(events(1)).as("the run wrote another table").isEmpty();
        runEnded(1, null);
        assertThat(events(1)).as("a run that cannot tell is checked").hasSize(1);
    }

    @Test
    void deleting_the_workflow_forgets_what_it_reported() throws Exception {
        apply(workflow(true));
        assertThat(remembered()).containsExactly("EN ATT");
        manifests.delete(1L, WF);
        assertThat(remembered()).isEmpty();
    }

    @Test
    void a_table_is_checked_after_its_own_write_and_a_fused_view_after_a_write_to_what_its_entity_reads() {
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply").then().statusCode(200);
        String other = "connector.t_ingit_other";
        assertThat(check.touched(TABLE, Set.of(TABLE))).isTrue();
        assertThat(check.touched(TABLE, Set.of(other))).isFalse();
        assertThat(check.touched(TABLE, null)).as("a run that cannot tell").isTrue();
        assertThat(check.touched("connector.v_ingitorder", Set.of(TABLE))).isTrue();
        assertThat(check.touched("connector.v_ingitorder", Set.of(other))).isFalse();
        assertThat(check.touched("connector.v_ingitnosuchentity", Set.of(other))).as("an entity not found").isTrue();
        assertThat(check.touched("connector.v_ingitnosuchentity", Set.of())).isFalse();
    }

    private List<JsonNode> events(long tenant) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT payload::text FROM core.event_outbox WHERE tenant_id = ? AND event_type = ? ORDER BY id")) {
            ps.setLong(1, tenant);
            ps.setString(2, EVENT);
            try (ResultSet rs = ps.executeQuery()) {
                List<JsonNode> out = new ArrayList<>();
                while (rs.next()) out.add(json.readTree(rs.getString(1)));
                return out;
            }
        }
    }

    private List<String> remembered() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM core.workflow_undeclared_value WHERE tenant_id = 1 AND workflow_name = ?")) {
            ps.setString(1, WF);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private void admin(String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** A write to the source after FORCE ROW LEVEL SECURITY: it reaches only the tenant it is made as. */
    private void asTenant(long tenant, String sql) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
                assertThat(st.executeUpdate(sql)).as(sql).isPositive();
            }
            c.commit();
        }
    }
}
