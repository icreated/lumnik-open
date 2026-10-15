package io.lumnik.hub.run;

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
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V68 (#952) replayed over a table that already holds several {@code Running} rows for one endpoint —
 * what an install whose lock once failed to serialize its runs could hold. Flyway applied V68 at
 * startup, when the table was clean, so this lifts the unique index, seeds the duplicates, runs the
 * SHIPPED script text, all in one transaction that is rolled back.
 */
@QuarkusTest
class RunQueueMigrationIT {

    private static final String SCRIPT = "/db/migration/connector/V68__run_queue_status.sql";
    private static final long TENANT = 9520L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void severalOldRunningRowsOfOneEndpointCollapseToTheNewestAndTheIndexIsBuilt() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DROP INDEX connector.uq_connector_run_in_flight");
                UUID connector = seedConnector(c);
                UUID epA = seedEndpoint(c, connector, "a");
                UUID epB = seedEndpoint(c, connector, "b");
                UUID oldest = seedRun(c, connector, epA, "Running", "2026-01-01T00:00:00Z");
                UUID middle = seedRun(c, connector, epA, "Running", "2026-02-01T00:00:00Z");
                UUID newest = seedRun(c, connector, epA, "Running", "2026-03-01T00:00:00Z");
                UUID elsewhere = seedRun(c, connector, epB, "Running", "2026-01-01T00:00:00Z");
                UUID finished = seedRun(c, connector, epA, "Completed", "2025-12-01T00:00:00Z");

                exec(c, script());   // over the migrated schema minus the one index lifted above
                exec(c, script());   // and again over the state it just built: re-runnable means idempotent

                assertThat(status(c, newest)).as("the most recent Running row survives").isEqualTo("Running");
                assertThat(status(c, oldest)).isEqualTo("Failed");
                assertThat(status(c, middle)).isEqualTo("Failed");
                assertThat(status(c, elsewhere)).as("another endpoint is untouched").isEqualTo("Running");
                assertThat(status(c, finished)).as("a finished row is untouched").isEqualTo("Completed");
                assertThat(text(c, "SELECT error_message FROM connector.connector_run WHERE id = '" + oldest + "'"))
                    .isEqualTo("superseded while enforcing one in-flight run per endpoint");
                assertThat(text(c, "SELECT ended_at IS NOT NULL FROM connector.connector_run WHERE id = '" + oldest + "'"))
                    .isEqualTo("t");
                assertThat(text(c, "SELECT resolution_exempt FROM connector.connector_run WHERE id = '" + oldest + "'"))
                    .as("it started and may have written: the resolution ledger must still cover it")
                    .isEqualTo("f");

                // Queued is accepted, with no start, when nothing is in flight for the endpoint...
                UUID epC = seedEndpoint(c, connector, "c");
                UUID epD = seedEndpoint(c, connector, "d");
                Savepoint accepted = c.setSavepoint();
                UUID queued = seedQueued(c, connector, epC);
                assertThat(status(c, queued)).isEqualTo("Queued");
                c.rollback(accepted);

                // ...but a Queued row that carries a start is refused.
                Savepoint refused = c.setSavepoint();
                assertThatThrownBy(() -> exec(c,
                    "INSERT INTO connector.connector_run (tenant_id, connector_id, endpoint_id, status, sync_mode, "
                  + "started_at, queued_at, triggered_by) VALUES (" + TENANT + ", '" + connector + "', '"
                  + epD + "', 'Queued', 'Incremental', now(), now(), 'manual')"))
                    .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getMessage()).contains("connector_run_started_at_check"));
                c.rollback(refused);

                // ...and one with no accept instant, which an expiry could never reach (NULL < cutoff is never true).
                Savepoint unaccepted = c.setSavepoint();
                assertThatThrownBy(() -> exec(c,
                    "INSERT INTO connector.connector_run (tenant_id, connector_id, endpoint_id, status, sync_mode, "
                  + "started_at, queued_at, triggered_by) VALUES (" + TENANT + ", '" + connector + "', '"
                  + epD + "', 'Queued', 'Incremental', NULL, NULL, 'manual')"))
                    .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getMessage()).contains("connector_run_queued_at_check"));
                c.rollback(unaccepted);

                // The unique index now refuses a second in-flight row for the endpoint (Queued behind Running).
                assertThatThrownBy(() -> seedQueued(c, connector, epA))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23505");
                        assertThat(e.getMessage()).contains("uq_connector_run_in_flight");
                    });
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void anExportRunMayBeQueuedAndQueuedCountsAsInFlight() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, script());   // replayed over the migrated schema...
                exec(c, script());   // ...and again over what the replay built: re-runnable means idempotent

                // exactly one CHECK still speaks of ended_at: the two old ones are gone, not duplicated
                assertThat(text(c, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'connector.export_run'::regclass "
                    + "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%ended_at%'")).isEqualTo("1");

                UUID exportId = UUID.randomUUID();
                Savepoint accepted = c.setSavepoint();
                UUID queued = seedExportRun(c, exportId, "mig952-export", "Queued", false);
                assertThat(status(c, queued, "export_run")).isEqualTo("Queued");
                c.rollback(accepted);

                Savepoint withEnd = c.setSavepoint();
                assertThatThrownBy(() -> seedExportRun(c, exportId, "mig952-export", "Queued", true))
                    .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getMessage()).contains("export_run_open_ended_at_check"));
                c.rollback(withEnd);

                Savepoint unaccepted = c.setSavepoint();
                assertThatThrownBy(() -> exec(c,
                    "INSERT INTO connector.export_run (tenant_id, export_id, export_name, scope, entity_name, "
                  + "destination_kind, manifest_sha256, trigger, artifact_time, status, started_at, queued_at) VALUES ("
                  + TENANT + ", '" + exportId + "', 'mig952-export', 's', 'e', 'sftp', 'x', 'manual', now(), "
                  + "'Queued', NULL, NULL)"))
                    .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getMessage()).contains("export_run_queued_at_check"));
                c.rollback(unaccepted);

                // The delivered/failed receipts are still demanded: the old CHECKs on them survived.
                Savepoint noReceipt = c.setSavepoint();
                assertThatThrownBy(() -> exec(c,
                    "INSERT INTO connector.export_run (tenant_id, export_id, export_name, scope, entity_name, "
                  + "destination_kind, manifest_sha256, trigger, artifact_time, status, started_at, ended_at) VALUES ("
                  + TENANT + ", '" + exportId + "', 'mig952-export', 's', 'e', 'sftp', 'x', 'manual', now(), "
                  + "'Completed', now(), now())"))
                    .isInstanceOf(SQLException.class);
                c.rollback(noReceipt);

                seedExportRun(c, exportId, "mig952-export", "Queued", false);
                assertThatThrownBy(() -> seedExportRun(c, UUID.randomUUID(), "mig952-export", "Running", false))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23505");
                        assertThat(e.getMessage()).contains("uq_export_run_in_flight");
                    });
            } finally {
                c.rollback();
            }
        }
    }

    private static UUID seedConnector(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, sync_mode, "
            + "skip_policy) VALUES ('" + id + "', " + TENANT + ", 'mig952-" + id + "', 'jdbc-generic', '{}'::jsonb, "
            + "true, ARRAY['mig952'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        return id;
    }

    private static UUID seedEndpoint(Connection c, UUID connector, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, name, path, config) VALUES ('"
            + id + "', '" + connector + "', " + TENANT + ", '" + name + "', '/', '{}'::jsonb)");
        return id;
    }

    private static UUID seedRun(Connection c, UUID connector, UUID endpoint, String status, String startedAt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO connector.connector_run (id, tenant_id, connector_id, endpoint_id, status, sync_mode, "
            + "started_at, ended_at, triggered_by) VALUES ('" + id + "', " + TENANT + ", '" + connector + "', '"
            + endpoint + "', '" + status + "', 'Incremental', '" + startedAt + "'::timestamptz, "
            + ("Running".equals(status) ? "NULL" : "'" + startedAt + "'::timestamptz") + ", 'manual')");
        return id;
    }

    private static UUID seedQueued(Connection c, UUID connector, UUID endpoint) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO connector.connector_run (id, tenant_id, connector_id, endpoint_id, status, sync_mode, "
            + "started_at, queued_at, triggered_by, definition_sha256) VALUES ('" + id + "', " + TENANT + ", '"
            + connector + "', '" + endpoint + "', 'Queued', 'Incremental', NULL, now(), 'manual', 'h')");
        return id;
    }

    private static UUID seedExportRun(Connection c, UUID exportId, String name, String status, boolean withEnd)
            throws SQLException {
        UUID id = UUID.randomUUID();
        boolean queued = "Queued".equals(status);
        exec(c, "INSERT INTO connector.export_run (id, tenant_id, export_id, export_name, scope, entity_name, "
            + "destination_kind, manifest_sha256, trigger, artifact_time, status, started_at, queued_at, ended_at) "
            + "VALUES ('" + id + "', " + TENANT + ", '" + exportId + "', '" + name + "', 's', 'e', 'sftp', 'x', "
            + "'manual', now(), '" + status + "', " + (queued ? "NULL" : "now()") + ", now(), "
            + (withEnd ? "now()" : "NULL") + ")");
        return id;
    }

    private static String status(Connection c, UUID run) throws SQLException {
        return status(c, run, "connector_run");
    }

    private static String status(Connection c, UUID run, String table) throws SQLException {
        return text(c, "SELECT status FROM connector." + table + " WHERE id = '" + run + "'");
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String script() throws Exception {
        try (InputStream in = RunQueueMigrationIT.class.getResourceAsStream(SCRIPT)) {
            if (in == null) throw new IllegalStateException(SCRIPT + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
