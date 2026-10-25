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
 * A {@code match:} key one source holds as a {@code bigint} ({@code 7}) and another as a {@code numeric(18,4)}
 * ({@code 7.0000}) is one record once fused (#1338): the view groups on the value without its trailing zeros and
 * {@code _canonical_key} encodes {@code 7}. The search marks are written from each source table on its own, so each
 * must say its key the same trimmed way, or a changed row is never projected. Same shape as
 * {@link SearchProjectionTemporalMatchKeyParityIT}.
 */
@QuarkusTest
class SearchProjectionNumericMatchKeyParityIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject AgroalDataSource appDs;

    @Test
    void aWholeAndADecimalKeyMakeOneRecord_andEveryMarkSaysItsKey() throws Exception {
        long tenant = 1_338_000_001L;
        String entityName = "SearchNumParity" + System.nanoTime();
        String whole = "t_search_num_a_" + System.nanoTime();
        String decimal = "t_search_num_b_" + System.nanoTime();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE connector." + whole + " (tenant_id bigint NOT NULL,"
                + " _ingested_at timestamptz NOT NULL DEFAULT now(), n int8, label text)");
            st.execute("CREATE TABLE connector." + decimal + " (tenant_id bigint NOT NULL,"
                + " _ingested_at timestamptz NOT NULL DEFAULT now(), num numeric(18,4), label text)");
            for (String t : List.of(whole, decimal)) {
                st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
                st.execute("CREATE POLICY " + t + "_iso ON connector." + t
                    + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
                st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
            }
        }
        yaml(tenant).body("kind: Entity\nname: " + entityName + "\nmatch: [no]\nscopes: [sales]\nsources:\n"
            + "  - table: " + whole + "\n    map: { n: no, label: label }\n"
            + "  - table: " + decimal + "\n    map: { num: no, label: label }\n")
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-num-parity");
        yaml(tenant).body(searchIndex(indexName, entityName, "label"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector." + whole + " (tenant_id, n, label) VALUES (" + tenant + ", 7, 'x')");
            st.execute("INSERT INTO connector." + decimal + " (tenant_id, num, label) VALUES (" + tenant + ", 7.0000, 'x')");
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
        assertThat(viewKeys).as("7 and 7.0000 from two sources are one record").containsExactly("1|1:7");
        assertThat(markKeys).as("both sources' marks say that record's key").containsExactlyElementsOf(viewKeys);
    }
}
