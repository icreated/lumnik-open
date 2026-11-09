package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
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
 * #1834: the reverse read of a lookup fan-out — "which fused rows does this lookup key reach" — runs once per page, so
 * it must reach the index the hub builds for it. The plan of the SQL the worker really runs is pinned here, for a text,
 * a whole-number and a temporal key, on a source of 100,000 rows. The read is on the admin pool (the tenant is in its own
 * predicate), so the IMMUTABLE rule is all it is held to: the LEAKPROOF one that refuses a decimal key for the
 * RLS-bound read (#1838) does not apply.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ReverseLookupPlanIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SchemaCardStore cards;
    @Inject MatchIndexes matchIndexes;
    @Inject MatchIndexReconciler reconciler;
    @Inject FusedRowReader fused;

    @Test
    void aTextKeyIsReadThroughTheIndexTheHubBuilt() throws Exception {
        assertThat(planOf("text", "text", "text", "K-5")).contains("Index Scan using t_rlp_text_res_mx_").doesNotContain("Seq Scan");
    }

    /** A number is compared as its canonical decimal text ({@code 7.0} and {@code 7} are one key): an expression the index carries. */
    @Test
    void aWholeNumberKeyIsReadThroughTheIndexTheHubBuilt() throws Exception {
        assertThat(planOf("number", "bigint", "bigint", "5")).contains("Index Scan using t_rlp_number_res_mx_").doesNotContain("Seq Scan");
    }

    @Test
    void anInstantKeyIsReadThroughTheIndexTheHubBuilt() throws Exception {
        assertThat(planOf("instant", "timestamptz", "timestamptz", "2026-01-06 00:00:00+00"))
            .contains("Index Scan using t_rlp_instant_res_mx_").doesNotContain("Seq Scan");
    }

    /** A date compared to a text side goes through {@code date::text}, which depends on DateStyle: PostgreSQL does not index it. */
    @Test
    void aTemporalKeyAgainstATextSideIsRefusedWithAReason() throws Exception {
        fixture("mixed", "date", "text");

        applyProcess("mixed").statusCode(400)
            .body("error", containsString("t_rlp_mixed_res"))
            .body("error", containsString("session"))
            .body("error", containsString("two dates"));
    }

    /** The source and the lookup (100,000 / 100 rows), an Entity joining them, a Process on it — then the plan, once the build has landed. */
    private String planOf(String kind, String sourceType, String lookupType, String key) throws Exception {
        fixture(kind, sourceType, lookupType);
        applyProcess(kind).statusCode(200);

        EntityManifest entity = matchIndexes.storedEntity("connector.v_rlp" + kind).orElseThrow();
        LookupReach reach = LookupReach.of(entity, fused.catalog(entity)).get(0);
        String plan = "";
        // The build is the hub's, in the background: ask again until it has landed (a regression is a plan that never loses its Seq Scan)
        for (int i = 0; i < 150; i++) {
            reconciler.sweep();
            plan = String.join("\n", explain(reach.pageSql(false), key));
            if (!plan.contains("Seq Scan")) break;
            Thread.sleep(200);
        }
        return plan;
    }

    private void fixture(String kind, String sourceType, String lookupType) throws Exception {
        String res = "t_rlp_" + kind + "_res";
        String reg = "t_rlp_" + kind + "_reg";
        String view = "connector.v_rlp" + kind;
        String sourceValue = switch (sourceType) {
            case "date" -> "DATE '2026-01-01' + (g % 1000)";
            case "bigint" -> "(g % 1000)";
            case "timestamptz" -> "TIMESTAMPTZ '2026-01-01 00:00:00+00' + (g % 1000) * interval '1 day'";
            default -> "'K-' || (g % 1000)";
        };
        String lookupValue = switch (lookupType) {
            case "timestamptz" -> "TIMESTAMPTZ '2026-01-01 00:00:00+00' + g * interval '1 day'";
            case "bigint" -> "g";
            default -> "'K-' || g";
        };
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS " + view);
            st.execute("DROP TABLE IF EXISTS connector." + res);
            st.execute("DROP TABLE IF EXISTS connector." + reg);
            st.execute("CREATE TABLE connector." + res + " (tenant_id bigint NOT NULL, id text, ref " + sourceType + ")");
            st.execute("CREATE TABLE connector." + reg + " (tenant_id bigint NOT NULL, instrument " + lookupType + ", calib text)");
            st.execute("INSERT INTO connector." + res + " SELECT 1, 'R-' || g, " + sourceValue + " FROM generate_series(1, 100000) g");
            st.execute("INSERT INTO connector." + reg + " SELECT 1, " + lookupValue + ", 'ok' FROM generate_series(1, 100) g");
            for (String t : new String[] {res, reg}) {
                st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
                st.execute("CREATE POLICY tenant_isolation ON connector." + t
                    + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
                st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app, lumnik_readonly");
                st.execute("ANALYZE connector." + t);
            }
            st.execute("DELETE FROM connector.dynamic_table WHERE tenant_id = 1 AND mapping_id IN ('rlp-" + kind + "-res', 'rlp-" + kind + "-reg')");
            st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                + "VALUES (1, 'rlp-" + kind + "-res', 'connector', '" + res + "', '{}'::jsonb), "
                + "(1, 'rlp-" + kind + "-reg', 'connector', '" + reg + "', '{}'::jsonb)");
            st.execute("DELETE FROM core.process_manifest WHERE tenant_id = 1 AND name = 'rlp-" + kind + "'");
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body("kind: Entity\nname: Rlp" + kind + "\nmatch: [id]\nscopes: [rlp" + kind + "]\nsources:\n"
                + "  - table: " + res + "\n    map: { id: id, ref: ref }\n"
                + "joins:\n  - table: " + reg + "\n    from: ref\n    to: instrument\n    map: { calib: calib }\n")
            .post("/api/entities/apply").then().statusCode(200);
        List<SchemaCard.Column> cols = List.of(new SchemaCard.Column("id", "text", "Id"), new SchemaCard.Column("calib", "text", "Calib"));
        cards.save(1L, "registry", new SchemaCard("rlp" + kind, List.of(new SchemaCard.Table(view, "Rlp" + kind, cols))));
    }

    private ValidatableResponse applyProcess(String kind) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body("apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: rlp-" + kind + " }\nspec:\n"
                + "  scope: rlp" + kind + "\n  on: { event: data.row.changed, table: connector.v_rlp" + kind + " }\n"
                + "  when: [ calib=ok ]\n  emit: { type: decision.rlp, message: \"ok\" }\n")
            .post("/api/processes/apply").then();
    }

    private List<String> explain(String sql, String key) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement("EXPLAIN (COSTS OFF) " + sql)) {
            ps.setLong(1, 1L);
            ps.setString(2, key);
            ps.setInt(3, 500);
            List<String> lines = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lines.add(rs.getString(1));
            }
            return lines;
        }
    }
}
