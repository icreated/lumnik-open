package io.lumnik.hub.flyway;

import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.util.List;

@ApplicationScoped
public class PlatformFlywayRunner {

    private static final Logger LOG = Logger.getLogger(PlatformFlywayRunner.class);

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    DataSource dataSource;
    @Inject ModuleManifestRegistry registry;

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
    }

    private void grantSchemaUsage(String schema) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA " + schema + " TO lumnik_app");
        } catch (java.sql.SQLException e) {
            LOG.warnf(e, "Could not grant USAGE on schema '%s' to lumnik_app — may already exist", schema);
        }
    }

    /**
     * Returns Flyway status (version + pending count) per registered schema.
     * Used by /api/platform/migrations for the lm CLI / TUI.
     */
    public java.util.Map<String, SchemaStatus> statusPerSchema() {
        java.util.Map<String, SchemaStatus> out = new java.util.LinkedHashMap<>();
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
            java.time.Instant lastApplied = null;
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

    public record SchemaStatus(String currentVersion, int pending, java.time.Instant lastApplied) {}
}
