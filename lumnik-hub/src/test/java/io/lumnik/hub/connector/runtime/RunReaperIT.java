package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit P13: a run hard-killed mid-flight stays at {@code Running}, and the scheduler's
 * {@code SELECT_DUE} excludes any connector with a Running run — silencing its schedule
 * forever, with no reaper anywhere. Single-replica by doctrine, a Running row at startup
 * cannot belong to a live run: the reaper fails it, restoring the schedule.
 */
@QuarkusTest
class RunReaperIT {

    private static final long TENANT = 4477L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject RunReaper reaper;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private UUID seedConnectorWithRun(String name, String runStatus) {
        UUID mappingId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('" + mappingId + "', " + TENANT + ", 'm_" + name + "', 'public." + name + "', 'yaml', 'x') "
           + "");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, "
           + "enabled, tags, sync_mode, skip_policy) VALUES ('" + connId + "', " + TENANT
           + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, '" + mappingId + "', true, "
           + "ARRAY['reaper'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by) "
           + "VALUES ('" + runId + "', " + TENANT + ", '" + connId + "', '" + runStatus + "', 'Incremental', 'manual')");
        return runId;
    }

    private String[] runRow(UUID runId) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT status, error_message, ended_at::text FROM connector.connector_run WHERE id = '" + runId + "'")) {
            rs.next();
            return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void a_running_row_left_by_a_dead_process_is_failed_at_startup() {
        UUID zombie = seedConnectorWithRun("zombie_src", "Running");

        reaper.reap();

        String[] row = runRow(zombie);
        assertThat(row[0]).as("a startup Running row is a zombie — must be failed").isEqualTo("Failed");
        assertThat(row[1]).as("the error must say what happened").contains("restarted");
        assertThat(row[2]).as("ended_at must be stamped").isNotNull();
    }

    @Test
    void terminal_rows_are_untouched() {
        UUID done = seedConnectorWithRun("done_src", "Completed");

        reaper.reap();

        assertThat(runRow(done)[0]).isEqualTo("Completed");
    }
}
