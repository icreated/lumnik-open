package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec test 8: proves the DATABASE itself rejects these row shapes — plain SQL against the migrated
 * schema, not through the store classes, so a bug in application code could never silently admit an
 * invalid row.
 */
@QuarkusTest
class SearchProjectionStateMigrationConstraintsIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void rejectsACancelRequestWithNoActiveRun() {
        assertThatThrownBy(() -> insertRaw(
            "INSERT INTO core.search_projection_state (tenant_id, search_index, status, cancel_requested_run_id) "
          + "VALUES (999, 'x', 'building', gen_random_uuid())"))
            .hasMessageContaining("search_projection_state_cancel_requested_check");
    }

    @Test
    void rejectsAnActiveRunIdWithNoPhase() {
        assertThatThrownBy(() -> insertRaw(
            "INSERT INTO core.search_projection_state (tenant_id, search_index, status, active_run_id) "
          + "VALUES (999, 'x', 'building', gen_random_uuid())"))
            .hasMessageContaining("search_projection_state_active_run_check");
    }

    @Test
    void rejectsAnActiveRunPhaseWithNoId() {
        assertThatThrownBy(() -> insertRaw(
            "INSERT INTO core.search_projection_state (tenant_id, search_index, status, active_run_phase) "
          + "VALUES (999, 'x', 'building', 'building')"))
            .hasMessageContaining("search_projection_state_active_run_check");
    }

    @Test
    void rejectsAPartiallyPopulatedLastRebuildTriad() {
        assertThatThrownBy(() -> insertRaw(
            "INSERT INTO core.search_projection_state (tenant_id, search_index, status, last_rebuild_outcome) "
          + "VALUES (999, 'x', 'ready', 'ready')"))
            .hasMessageContaining("search_projection_state_last_rebuild_check");
    }

    private void insertRaw(String sql) throws SQLException {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
