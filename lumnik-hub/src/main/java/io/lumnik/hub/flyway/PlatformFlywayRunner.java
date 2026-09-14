package io.lumnik.hub.flyway;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PlatformFlywayRunner {

    private static final Logger LOG = Logger.getLogger(PlatformFlywayRunner.class);

    @Inject
    @DataSource("admin")
    AgroalDataSource dataSource;
    @Inject ModuleManifestRegistry registry;
    @Inject Scheduler scheduler;

    // Priority(2) — runs after registry (Priority 1)
    void onStart(@Observes @Priority(2) StartupEvent ev) {
        for (ModuleManifest manifest : registry.all()) {
            List<String> locations = manifest.flywayLocations();
            if (locations.isEmpty()) continue;

            LOG.infof("Running Flyway for module '%s' (schema: %s, locations: %s)",
                manifest.id(), manifest.schema(), locations);

            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(manifest.schema())
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(false)
                .outOfOrder(true)        // allow filling gaps left by seed migrations (V4 after V100)
                .validateOnMigrate(true)
                .load();

            var result = flyway.migrate();
            LOG.infof("Module '%s': %d migration(s) applied", manifest.id(), result.migrationsExecuted);
            grantSchemaUsage(manifest.schema());
        }
        // quarkus.scheduler.start-mode=halted keeps every @Scheduled bean off until this line:
        // a scheduled job's first tick otherwise races this very loop on a fresh database
        // (its own executor thread starts independently of the StartupEvent ordering above).
        // quarkus.scheduler.enabled=false (SchedulerPausedProfile and friends, IT-only) swaps in
        // a no-op Scheduler that throws on resume() — nothing to un-halt, not a boot failure.
        try {
            scheduler.resume();
        } catch (UnsupportedOperationException e) {
            LOG.debugf("No scheduler to resume: %s", e.getMessage());
        }
    }

    private void grantSchemaUsage(String schema) {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA " + schema + " TO lumnik_app");
        } catch (SQLException e) {
            LOG.warnf(e, "Could not grant USAGE on schema '%s' to lumnik_app — may already exist", schema);
        }
    }

    /**
     * Returns Flyway status (version + pending count) per registered schema.
     * Used by /api/platform/migrations for the lm CLI / TUI.
     */
    public Map<String, SchemaStatus> statusPerSchema() {
        Map<String, SchemaStatus> out = new LinkedHashMap<>();
        for (ModuleManifest manifest : registry.all()) {
            if (manifest.flywayLocations().isEmpty()) continue;
            out.put(manifest.schema(), statusFor(manifest));
        }
        return out;
    }

    private SchemaStatus statusFor(ModuleManifest manifest) {
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(manifest.schema())
                .locations(manifest.flywayLocations().toArray(String[]::new))
                .baselineOnMigrate(false)
                .outOfOrder(true)
                .validateOnMigrate(false)
                .load();
            String current = "—";
            int pending = 0;
            Instant lastApplied = null;
            for (var inf : flyway.info().all()) {
                if (inf.getInstalledOn() != null) {
                    current = inf.getVersion() != null ? inf.getVersion().toString() : inf.getDescription();
                    lastApplied = inf.getInstalledOn().toInstant();
                }
                if ("PENDING".equals(inf.getState().name())) pending++;
            }
            return new SchemaStatus(current, pending, lastApplied);
        } catch (Exception e) {
            return new SchemaStatus("ERR:" + e.getClass().getSimpleName(), 0, null);
        }
    }

    public record SchemaStatus(String currentVersion, int pending, Instant lastApplied) {}
}
