package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V57's own grant comment (I1, #671 final review): lumnik_app gets a read grant on {@code
 * core.search_projection_state} "and nothing else" — proved here under the REAL non-owner
 * {@code lumnik_app} role ({@link RlsEnforcedProfile}, the same harness {@code
 * SearchProjectionMarkReMarkRlsIT} already uses for the sibling mark table), since the plain
 * shared DevServices container connects as a superuser-equivalent owner and would let every one
 * of these statements through regardless of whether the GRANT/REVOKE pair actually applies.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class SearchProjectionStateGrantRlsIT {

    @Inject AgroalDataSource defaultDs; // request-scoped, connects as lumnik_app under this profile

    @Test
    void lumnikAppCanSelectButNotWriteDirectly() throws SQLException {
        try (Connection c = defaultDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM core.search_projection_state")) {
            assertThat(ps.executeQuery().next()).as("SELECT is granted").isTrue();
        }
        try (Connection c = defaultDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_state (tenant_id, search_index) VALUES (1, 'grant-test-671')")) {
            assertThatThrownBy(ps::executeUpdate)
                .as("INSERT must stay refused — the default-privileges baseline grants it, V57 revokes it back")
                .isInstanceOf(SQLException.class);
        }
        try (Connection c = defaultDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE core.search_projection_state SET status = 'ready' WHERE tenant_id = 1")) {
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class);
        }
        try (Connection c = defaultDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_projection_state WHERE tenant_id = 1")) {
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class);
        }
    }
}
