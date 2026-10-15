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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V57 (#671, design doc §1/§3): the migration's own SQL migrates a mark_kind='PURGE' row into
 * core.search_index_purge before tightening the CHECK, never merely discarding it — but this test
 * runs against the already-migrated schema (Flyway ran before @QuarkusTest boots), so it cannot
 * seed a PURGE row ahead of V57 and observe that INSERT...SELECT/DELETE step itself; that step only
 * ever runs once, against a pre-V57 database. What this test actually proves is the schema V57
 * leaves behind: the tightened CHECK refuses a fresh PURGE insert, and KEY/ALL are still allowed.
 */
@QuarkusTest
class V57PurgeMigrationIT {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void tightenedCheckRefusesAFreshPurgeInsert() throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO core.search_projection_mark "
                  + "(tenant_id, search_index, entity, mark_kind, canonical_key, reason) "
                  + "VALUES (1, 'idx', 'entity', 'PURGE', '', 'test')")) {
                assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .extracting(t -> ((SQLException) t).getSQLState())
                    .as("23514 = check_violation, not merely some SQLException")
                    .isEqualTo("23514");
            }
        }
    }

    @Test
    void checkAllowsKeyAndAll() throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO core.search_projection_mark "
                  + "(tenant_id, search_index, entity, mark_kind, canonical_key, reason) "
                  + "VALUES (1, 'idx', 'entity', 'ALL', '', 'test')");
                // A KEY row needs a non-empty canonical_key or it fails V56's own
                // search_projection_mark_key_check biconditional — a separate, pre-existing
                // constraint, not the one V57 tightens.
                st.executeUpdate(
                    "INSERT INTO core.search_projection_mark "
                  + "(tenant_id, search_index, entity, mark_kind, canonical_key, reason) "
                  + "VALUES (1, 'idx', 'entity', 'KEY', 'k1', 'test')");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'idx'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            c.createStatement().executeUpdate(
                "DELETE FROM core.search_projection_mark WHERE tenant_id = 1 AND search_index = 'idx'");
        }
    }
}
