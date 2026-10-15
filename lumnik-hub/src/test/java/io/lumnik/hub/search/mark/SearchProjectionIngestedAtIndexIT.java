package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #945: the mark producer's {@code _ingested_at > watermark} scan reads every row of a table the tenant
 * owns, every minute, unless {@code (tenant_id, _ingested_at)} is indexed. The reconciler — which already
 * enumerates the tables a valid SearchIndex observes — creates that index, concurrently, before it
 * scans the table, and repairs one a failed build left invalid.
 */
@QuarkusTest
class SearchProjectionIngestedAtIndexIT {

    @Inject SearchProjectionReconciler reconciler;
    @Inject IngestedAtIndex ingestedAtIndex;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private String indexName(String table) {
        return table + "_ing_idx";
    }

    /** Null = no such index; otherwise its pg_index.indisvalid. */
    private Boolean indexValidity(String table) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT i.indisvalid FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
               + "JOIN pg_namespace n ON n.oid = ic.relnamespace "
               + "WHERE n.nspname = 'connector' AND ic.relname = ?")) {
            ps.setString(1, indexName(table));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private String indexDef(String table) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT indexdef FROM pg_indexes WHERE schemaname = 'connector' AND indexname = ?")) {
            ps.setString(1, indexName(table));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String observedTable(long tenant, String label) throws Exception {
        String entityName = "SearchIdx945" + label + System.nanoTime();
        String table = "t_search_idx_945" + label.toLowerCase() + "_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);
        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(searchIndex(unique("ing-idx"), entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        return table;
    }

    @Test
    void theSweepIndexesTenantAndIngestedAtOnAnObservedTable() throws Exception {
        long tenant = 945_001;
        String table = observedTable(tenant, "A");
        insertRow(adminDs, table, tenant, "C1", "Alpha");
        assertThat(indexValidity(table)).as("precondition: nothing indexes the table yet").isNull();

        reconciler.sweep();

        assertThat(indexValidity(table)).as("the index exists and is valid").isTrue();
        assertThat(indexDef(table)).contains("(tenant_id, _ingested_at)").doesNotContain("UNIQUE");

        reconciler.sweep();
        assertThat(indexValidity(table)).as("a second sweep leaves a valid index alone").isTrue();
    }

    @Test
    void anInvalidIndexLeftByAnInterruptedBuildIsDroppedThenRebuiltOnTheNextSweep() throws Exception {
        long tenant = 945_002;
        String table = observedTable(tenant, "B");
        leaveAnInvalidIndex(table, "(tenant_id, _ingested_at)");
        assertThat(indexValidity(table)).as("precondition: an INVALID index holds the name").isFalse();

        reconciler.sweep();
        assertThat(indexValidity(table)).as("the first sweep drops the invalid index").isNull();

        reconciler.sweep();
        assertThat(indexValidity(table)).as("the next sweep rebuilds it").isTrue();
        assertThat(indexDef(table)).doesNotContain("UNIQUE");
    }

    /** What a CREATE INDEX CONCURRENTLY that did not finish leaves: an open write transaction on the table makes
     *  the build wait for it, and the statement timeout cuts the wait -- an INVALID index remains. */
    private void leaveAnInvalidIndex(String table, String columns) throws Exception {
        try (Connection holder = adminDs.getConnection(); Connection builder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (0, 'H', 'h')");
            }
            builder.setAutoCommit(true);
            try (Statement st = builder.createStatement()) {
                st.execute("SET statement_timeout = '500ms'");
                st.execute("CREATE INDEX CONCURRENTLY " + indexName(table) + " ON connector." + table + " " + columns);
            } catch (SQLException expected) {
                // canceled while waiting: the invalid index remains
            } finally {
                holder.rollback();
                // The session setting rides the pooled connection into whichever IT borrows it next, and turns
                // its lock waits into statement timeouts (57014 where 55P03 is expected).
                try (Statement st = builder.createStatement()) {
                    st.execute("RESET statement_timeout");
                }
            }
        }
    }

    @Test
    void anInvalidObjectUnderOurNameThatIsNotOurIndexIsNotDropped() throws Exception {
        long tenant = 945_005;
        String table = observedTable(tenant, "E");
        leaveAnInvalidIndex(table, "(src_code)");
        assertThat(indexValidity(table)).as("precondition: an INVALID foreign index holds the name").isFalse();

        reconciler.sweep();
        reconciler.sweep();

        assertThat(indexValidity(table)).as("left untouched: ownership is decided before validity").isFalse();
        assertThat(indexDef(table)).contains("(src_code)");
    }

    @Test
    void indexingAnObservedTableThatIsGoneIsASkipNotAFailure() throws Exception {
        long tenant = 945_003;
        String table = observedTable(tenant, "C");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE connector." + table + " CASCADE");
        }

        ingestedAtIndex.ensure(table); // must not throw

        assertThat(indexValidity(table)).isNull();
    }

    @Test
    void aValidObjectUnderOurNameThatIsNotOurIndexIsLeftAloneAndNotTakenForIt() throws Exception {
        long tenant = 945_004;
        String table = observedTable(tenant, "D");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE INDEX " + indexName(table) + " ON connector." + table + " (src_code)");
        }

        reconciler.sweep();

        assertThat(indexValidity(table)).as("still there, not dropped").isTrue();
        assertThat(indexDef(table)).as("and not rewritten into ours").contains("(src_code)");
    }
}
