package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Fails runs left at {@code Running} by a previous process (audit P13). The scheduler's
 * SELECT_DUE excludes any connector with a Running run, so a hard kill — or the shutdown
 * race where finalizeRun loses to datasource teardown — silenced that connector's schedule
 * forever, with nothing anywhere to clear it. Single-replica by doctrine: at startup, a
 * Running row cannot belong to a live run.
 */
@ApplicationScoped
public class RunReaper {

    private static final Logger LOG = Logger.getLogger(RunReaper.class);

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** After Flyway (@Priority(2)) — the run table must exist; before the first scheduler tick
     *  matters not at all (the tick only reads what this repairs). */
    void onStart(@Observes @Priority(6) StartupEvent ev) {
        int n = reap();
        if (n > 0) LOG.warnf("Reaped %d run(s) left 'Running' by a previous process — their connectors are schedulable again", n);
    }

    public int reap() {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            return st.executeUpdate(
                "UPDATE connector.connector_run SET status = 'Failed', ended_at = now(), " +
                "error_message = 'reaped at startup: the hub restarted while this run was in flight' " +
                "WHERE status = 'Running'");
        } catch (SQLException e) {
            // Fail open on the reap itself (a broken reaper must not block boot), but LOUDLY:
            // an unreaped zombie means a silenced schedule.
            LOG.error("RunReaper failed — connectors with a stale Running run stay unscheduled", e);
            return 0;
        }
    }
}
