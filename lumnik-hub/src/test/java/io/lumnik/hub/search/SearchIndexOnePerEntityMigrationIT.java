package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V61 (#832) replayed over a table that already holds duplicates — the state an install that ran
 * two indexes over one entity is in when it upgrades. Flyway applied V61 at startup, when the table
 * was empty, so this test lifts the unique index, seeds duplicates, then runs the SHIPPED script
 * text, all in one transaction that is rolled back.
 */
@QuarkusTest
class SearchIndexOnePerEntityMigrationIT {

    private static final String SCRIPT = "/db/migration/core/V61__search_index_one_per_entity.sql";
    private static final long TENANT = 8321;
    private static final long OTHER_TENANT = 8322;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void duplicatesKeepTheEarliestAndPurgeTheRestAndTheIndexIsBuilt() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP INDEX core.search_index_one_per_entity");
                }
                seed(c, TENANT, "first", "Mig832Entity", "2026-01-01");
                seed(c, TENANT, "second", "mig832entity", "2026-02-01");        // same entity, other spelling
                seed(c, TENANT, "third", "Mig832Entity", "2026-03-01");
                seed(c, TENANT, "elsewhere", "Mig832Other", "2026-04-01");       // a different entity: untouched
                seed(c, OTHER_TENANT, "theirs", "Mig832Entity", "2026-05-01");   // another tenant: untouched
                // The oldest is invalid, a newer one works: the one that works survives.
                seed(c, TENANT, "broken-old", "Mig832Pair", "2026-01-01", "invalid");
                seed(c, TENANT, "working-new", "Mig832Pair", "2026-07-01");
                seed(c, TENANT, "working-newer", "Mig832Pair", "2026-08-01");

                try (Statement st = c.createStatement()) {
                    st.execute(script());
                }

                assertThat(names(c, "SELECT name FROM core.search_index WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ") ORDER BY name"))
                    .containsExactly("elsewhere", "first", "theirs", "working-new");
                assertThat(names(c, "SELECT name FROM core.search_index_purge WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ") ORDER BY name"))
                    .containsExactly("broken-old", "second", "third", "working-newer");

                // The slot is now held by the table itself, not only by the apply door.
                assertThatThrownBy(() -> seed(c, TENANT, "late", "Mig832Entity", "2026-06-01"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23505");
                        assertThat(e.getMessage()).contains("search_index_one_per_entity");
                    });
            } finally {
                c.rollback();
            }
        }
    }

    private static void seed(Connection c, long tenant, String name, String entity, String createdAt) throws SQLException {
        seed(c, tenant, name, entity, createdAt, "valid");
    }

    private static void seed(Connection c, long tenant, String name, String entity, String createdAt, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core.search_index (tenant_id, name, entity, language, manifest_yaml, manifest, created_at, "
              + "status, invalid_reason) VALUES (?, ?, ?, 'simple', '', '{}'::jsonb, ?::timestamptz, ?, "
              + "CASE WHEN ? = 'invalid' THEN 'seeded' END)")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, entity);
            ps.setString(4, createdAt + "T00:00:00Z");
            ps.setString(5, status);
            ps.setString(6, status);
            ps.executeUpdate();
        }
    }

    private static List<String> names(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String script() throws Exception {
        try (InputStream in = SearchIndexOnePerEntityMigrationIT.class.getResourceAsStream(SCRIPT)) {
            assertThat(in).as("shipped migration " + SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
