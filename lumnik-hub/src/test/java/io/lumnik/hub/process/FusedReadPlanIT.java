package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #1833: the consumer reads the fused row once per source event, so the read must reach the sources' indexes. Measured
 * at 100,000 rows per source: a filter on {@code _canonical_key} (an expression of the match columns) is applied to each
 * source as a filter on an expression — a sequential scan of both, 99,999 rows removed each; the same row by its match
 * column is an index scan of both. The plan of the SQL the consumer really runs is pinned here — on the index the hub
 * builds itself (#1838), for a text, a numeric and a temporal key.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class FusedReadPlanIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject SchemaCardStore cards;
    @Inject MatchIndexReconciler reconciler;

    @Test
    void aTextKeyReadUsesTheIndexTheHubBuilt() throws Exception {
        String plan = planOf("text", "text", "text", "text", "C-5000");

        assertThat(plan).contains("Index Scan using t_planit_text_erp_mx_")
            .contains("Index Scan using t_planit_text_crm_mx_")
            .doesNotContain("Seq Scan");
    }

    /** One source an int4, the other an int8: the view groups by the widened int4, a cast PostgreSQL knows is LEAKPROOF. */
    @Test
    void aWholeNumberKeyReadUsesTheIndexTheHubBuilt() throws Exception {
        String plan = planOf("bigint", "int4", "int8", "bigint", "5000");

        assertThat(plan).contains("Index Scan using t_planit_bigint_erp_mx_")
            .contains("Index Scan using t_planit_bigint_crm_mx_")
            .doesNotContain("Seq Scan");
    }

    @Test
    void anInstantKeyReadUsesTheIndexTheHubBuilt() throws Exception {
        String plan = planOf("instant", "timestamptz", "timestamptz", "timestamptz", "2026-03-05 00:00:00+00");

        assertThat(plan).contains("Index Scan using t_planit_instant_erp_mx_")
            .contains("Index Scan using t_planit_instant_crm_mx_")
            .doesNotContain("Seq Scan");
    }

    /**
     * Under RLS a predicate that calls a function that is not LEAKPROOF is never an index condition (measured: the same
     * index on {@code trim_scale(code::numeric)} is left unused, a sequential scan of both sources). The door says so
     * instead of building an index that serves nothing.
     */
    @Test
    void aNumericKeyIsRefusedBecauseNoIndexCouldServeItUnderRowLevelSecurity() throws Exception {
        fixture("numeric", "int4", "numeric(12,4)", "numeric");

        applyProcess("numeric").statusCode(400)
            .body("error", containsString("t_planit_numeric_erp"))
            .body("error", containsString("trim_scale"))
            .body("error", containsString("match key"));
    }

    /** A date beside a timestamptz is read as an instant through {@code timezone()}, which is not LEAKPROOF either. */
    @Test
    void aDateKeyBesideAnInstantIsRefusedForTheSameReason() throws Exception {
        fixture("temporal", "date", "timestamptz", "timestamptz");

        applyProcess("temporal").statusCode(400)
            .body("error", containsString("t_planit_temporal_erp"))
            .body("error", containsString("timezone"));
    }

    /**
     * Two sources with a key of the given types, 100,000 rows each, no index declared; a Process on the Entity; then the
     * plan of the SQL the consumer really runs, once the hub's background build has landed.
     */
    private String planOf(String kind, String erpType, String crmType, String fusedType, String key) throws Exception {
        fixture(kind, erpType, crmType, fusedType);
        applyProcess(kind).statusCode(200);

        // The build is the hub's, in the background: ask again until it has landed (a regression is a plan that never loses its Seq Scan)
        String sql = FusedRowReader.readSql(new FusedRowReader.FusedView("connector.v_planit" + kind, List.of("code"), List.of(fusedType)));
        String plan = String.join("\n", explain(sql, key));
        for (int i = 0; i < 150 && plan.contains("Seq Scan"); i++) {
            reconciler.sweep();
            Thread.sleep(200);
            plan = String.join("\n", explain(sql, key));
        }
        return plan;
    }

    /** The two source tables (100,000 rows, RLS on, no index), registered as writer targets, the Entity over them and its card. */
    private void fixture(String kind, String erpType, String crmType, String fusedType) throws Exception {
        String erp = "t_planit_" + kind + "_erp";
        String crm = "t_planit_" + kind + "_crm";
        String entity = "PlanIt" + kind;
        String view = "connector.v_planit" + kind;
        String value = switch (erpType) {
            case "date" -> "DATE '2026-01-01' + g";
            case "int4" -> "g";
            case "timestamptz" -> "TIMESTAMPTZ '2026-01-01 00:00:00+00' + g * interval '1 day'";
            default -> "'C-' || g";
        };
        String crmValue = switch (crmType) {
            case "timestamptz" -> "TIMESTAMPTZ '2026-01-01 00:00:00+00' + g * interval '1 day'";
            case "text" -> "'C-' || g";
            default -> "g";
        };
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS " + view);
            st.execute("DROP TABLE IF EXISTS connector." + erp);
            st.execute("DROP TABLE IF EXISTS connector." + crm);
            st.execute("CREATE TABLE connector." + erp + " (tenant_id bigint NOT NULL, code " + erpType + ", credit_limit text)");
            st.execute("CREATE TABLE connector." + crm + " (tenant_id bigint NOT NULL, customer " + crmType + ", segment text)");
            st.execute("INSERT INTO connector." + erp + " SELECT 1, " + value + ", '500' FROM generate_series(1, 100000) g");
            st.execute("INSERT INTO connector." + crm + " SELECT 1, " + crmValue + ", 'GOLD' FROM generate_series(1, 100000) g");
            for (String t : new String[] {erp, crm}) {
                st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
                st.execute("CREATE POLICY tenant_isolation ON connector." + t
                    + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
                st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app, lumnik_readonly");
                st.execute("ANALYZE connector." + t);
            }
            st.execute("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 AND mapping_id IN ('planit-" + kind + "-erp', 'planit-" + kind + "-crm')");
            for (String t : new String[] {erp, crm}) {
                st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                    + "VALUES (1, 'planit-" + kind + "-" + (t.equals(erp) ? "erp" : "crm") + "', 'connector', '" + t + "', '{}'::jsonb)");
            }
            st.execute("DELETE FROM core.process_manifest WHERE tenant_id = 1 AND name = 'planit-" + kind + "'");
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body("kind: Entity\nname: " + entity + "\nmatch: [code]\nscopes: [planit" + kind + "]\nsources:\n"
                + "  - table: " + erp + "\n    map: { code: code, credit_limit: credit_limit }\n"
                + "  - table: " + crm + "\n    map: { customer: code, segment: segment }\n")
            .post("/api/entities/apply").then().statusCode(200);
        List<SchemaCard.Column> cols = List.of(new SchemaCard.Column("code", fusedType.startsWith("numeric") ? "numeric" : fusedType, "Code"),
            new SchemaCard.Column("segment", "text", "Segment"));
        cards.save(1L, "registry", new SchemaCard("planit" + kind, List.of(new SchemaCard.Table(view, entity, cols))));
    }

    private ValidatableResponse applyProcess(String kind) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body("apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: planit-" + kind + " }\nspec:\n"
                + "  scope: planit" + kind + "\n  on: { event: data.row.changed, table: connector.v_planit" + kind + " }\n"
                + "  when: [ segment=GOLD ]\n  emit: { type: decision.planit, message: \"GOLD\" }\n")
            .post("/api/processes/apply").then();
    }

    private List<String> explain(String sql, String key) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, 1L);
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN (COSTS OFF) " + sql)) {
                    ps.setString(1, key);
                    List<String> lines = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) lines.add(rs.getString(1));
                    }
                    return lines;
                }
            }
        });
    }
}
