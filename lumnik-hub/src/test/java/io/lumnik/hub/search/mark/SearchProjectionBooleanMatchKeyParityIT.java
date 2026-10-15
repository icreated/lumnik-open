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
import java.sql.SQLException;
import java.sql.Statement;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code-review finding (post-#671): {@link EntityViewCompiler}'s compiled view casts every match
 * column to {@code ::text} before building {@code _canonical_key} — for a {@code boolean} physical
 * column, Postgres's explicit {@code ::text} cast gives {@code "true"}/{@code "false"}, but reading
 * the SAME raw column via pgjdbc's {@code ResultSet.getString()} (boolean is the one type with its
 * own non-default wire-format text, {@code "t"}/{@code "f"}) used to give a DIFFERENT string.
 * {@code markMatchKeys} now selects {@code col::text} explicitly, matching the view — this
 * proves the mark it writes and the view's own {@code _canonical_key} agree end to end, not merely
 * that {@code MatchKeyEncoding.encode} agrees with itself on two Java strings
 * ({@code MatchKeyEncodingViewParityIT}'s own scope, which never drives this ingestion path at all).
 */
@QuarkusTest
class SearchProjectionBooleanMatchKeyParityIT {

    private static final String REASON = "ingestion-run";

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject AgroalDataSource appDs; // default datasource: lumnik_app, RLS-enforced

    private void fireRun(long tenant) throws SQLException {
        hook.processTenant(tenant);
    }

    private String markCanonicalKey(long tenant, String indexName) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT canonical_key FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'KEY' AND reason = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            ps.setString(3, REASON);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("a KEY mark must have been produced for this pair").isTrue();
                return rs.getString(1);
            }
        }
    }

    private String viewCanonicalKey(long tenant, String qualifiedView) throws Exception {
        try (Connection c = appDs.getConnection(); Statement begin = c.createStatement()) {
            begin.execute("BEGIN");
            begin.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT \"_canonical_key\" FROM " + qualifiedView)) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("the view must have exactly one row for this tenant").isTrue();
                    String key = rs.getString(1);
                    begin.execute("COMMIT");
                    return key;
                }
            }
        }
    }

    @Test
    void booleanMatchColumnProducesTheSameCanonicalKeyAsTheView() throws Exception {
        long tenant = 671_920_001L;
        String entityName = "SearchBoolParity671" + System.nanoTime();
        String table = "t_search_bool_parity_" + System.nanoTime();
        String view = EntityViewCompiler.viewName(entityName);

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE connector." + table + " ("
                + "tenant_id bigint NOT NULL, _ingested_at timestamptz NOT NULL DEFAULT now(), "
                + "src_flag boolean)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
        }

        String manifest = "kind: Entity\n"
            + "name: " + entityName + "\n"
            + "match: [flag]\n"
            + "scopes: [sales]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + "      src_flag: flag\n";
        yaml(tenant).body(manifest).post("/api/entities/apply").then().statusCode(200);

        String indexName = unique("mark-bool-parity");
        yaml(tenant).body(searchIndex(indexName, entityName, "flag"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_flag) VALUES (?, true)")) {
            ps.setLong(1, tenant);
            ps.executeUpdate();
        }

        fireRun(tenant);

        String markKey = markCanonicalKey(tenant, indexName);
        String viewKey = viewCanonicalKey(tenant, view);
        assertThat(markKey).as("the ingested mark's own canonical_key").isEqualTo(viewKey);
    }
}
