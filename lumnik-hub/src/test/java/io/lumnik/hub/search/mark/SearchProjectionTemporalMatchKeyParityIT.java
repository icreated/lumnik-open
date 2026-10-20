package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code match:} key one source holds as a {@code date} and another as a {@code timestamptz} is one record once
 * fused: the view groups on the aligned value (the day is its midnight UTC) and {@code _canonical_key} encodes that
 * value's text (#1325, #1326 review). The search marks are written from each source table on its own, so each must
 * say its key the same aligned way, and in UTC as the projection reads the view, or a changed row is never projected.
 * Same shape as {@link SearchProjectionBooleanMatchKeyParityIT}.
 */
@QuarkusTest
class SearchProjectionTemporalMatchKeyParityIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject AgroalDataSource appDs;

    @Test
    void aDateAndATimestampKeyMakeOneRecord_andEveryMarkSaysItsKey() throws Exception {
        long tenant = 1_326_000_001L;
        String entityName = "SearchDayParity" + System.nanoTime();
        String days = "t_search_day_a_" + System.nanoTime();
        String stamps = "t_search_day_b_" + System.nanoTime();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE connector." + days + " (tenant_id bigint NOT NULL,"
                + " _ingested_at timestamptz NOT NULL DEFAULT now(), d date, label text)");
            st.execute("CREATE TABLE connector." + stamps + " (tenant_id bigint NOT NULL,"
                + " _ingested_at timestamptz NOT NULL DEFAULT now(), at timestamptz, label text)");
            for (String t : List.of(days, stamps)) {
                st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
                st.execute("CREATE POLICY " + t + "_iso ON connector." + t
                    + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
                st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
            }
        }
        yaml(tenant).body("kind: Entity\nname: " + entityName + "\nmatch: [day]\nscopes: [sales]\nsources:\n"
            + "  - table: " + days + "\n    map: { d: day, label: label }\n"
            + "  - table: " + stamps + "\n    map: { at: day, label: label }\n")
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-day-parity");
        yaml(tenant).body(searchIndex(indexName, entityName, "label"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector." + days + " (tenant_id, d, label) VALUES (" + tenant + ", '2026-07-01', 'x')");
            st.execute("INSERT INTO connector." + stamps + " (tenant_id, at, label) VALUES (" + tenant + ", '2026-07-01 00:00+00', 'x')");
        }

        hook.processTenant(tenant);

        List<String> viewKeys = new ArrayList<>();
        try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN");
            st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            st.execute("SET LOCAL TimeZone = 'UTC'");   // as the projection reads the view (TenantRlsScope)
            try (ResultSet rs = st.executeQuery("SELECT \"_canonical_key\" FROM " + EntityViewCompiler.viewName(entityName))) {
                while (rs.next()) viewKeys.add(rs.getString(1));
            }
            st.execute("COMMIT");
        }
        List<String> markKeys = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT canonical_key FROM core.search_projection_mark"
                    + " WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'KEY'")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) markKeys.add(rs.getString(1));
            }
        }
        assertThat(viewKeys).as("the day from two sources is one record").hasSize(1);
        assertThat(markKeys).as("both sources' marks say that record's key").containsExactlyElementsOf(viewKeys);
    }
}
