package io.lumnik.hub.api.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * #952 slice 2: {@code POST /api/platform/runs/{id}/cancel} on a {@code Queued} run. A queued run belongs
 * to no worker, so the door ends it at once (Cancelled, the endpoint stamped in the same transaction)
 * instead of refusing it as "already ended"; one that a dispatcher started while the cancel waited for
 * its row falls through to the in-flight path.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class RunCancelQueuedIT {

    private static final long TENANT = 1L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject MeterRegistry registry;

    private UUID connectorId;
    private UUID endpointId;

    @AfterEach
    void clean() {
        if (connectorId != null) {
            Jdbc.update(adminDs, "clean", "DELETE FROM connector.connector WHERE id = ?", ps -> ps.setObject(1, connectorId));
        }
    }

    private void seedEndpoint(long tenant) {
        connectorId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        Jdbc.update(adminDs, "connector", "INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, "
            + "tags, sync_mode, skip_policy) VALUES (?, ?, ?, 'jdbc-generic', '{}'::jsonb, true, ARRAY['cq952'], "
            + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)",
            ps -> { ps.setObject(1, connectorId); ps.setLong(2, tenant); ps.setString(3, "cancel-queued-" + connectorId); });
        Jdbc.update(adminDs, "endpoint", "INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, name, path, config) "
            + "VALUES (?, ?, ?, 'e', '/', '{}'::jsonb)", ps -> { ps.setObject(1, endpointId); ps.setObject(2, connectorId); ps.setLong(3, tenant); });
    }

    private UUID queuedRun(long tenant) {
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "queued run", "INSERT INTO connector.connector_run (id, tenant_id, connector_id, endpoint_id, status, "
            + "sync_mode, started_at, queued_at, triggered_by) VALUES (?, ?, ?, ?, 'Queued', 'Incremental', NULL, now(), 'manual')",
            ps -> { ps.setObject(1, id); ps.setLong(2, tenant); ps.setObject(3, connectorId); ps.setObject(4, endpointId); });
        return id;
    }

    private String status(UUID run) {
        return Jdbc.queryOne(adminDs, "status", "SELECT status FROM connector.connector_run WHERE id = ?",
            ps -> ps.setObject(1, run), rs -> rs.getString(1)).orElseThrow();
    }

    @Test
    void a_queued_run_is_cancelled_in_one_step() {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);
        double before = registry.counter("lumnik_runs_total", "status", "Cancelled").count();

        given().headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + run + "/cancel")
            .then().statusCode(202).body("status", equalTo("cancelled"));

        assertThat(registry.counter("lumnik_runs_total", "status", "Cancelled").count() - before).isEqualTo(1.0);

        var row = Jdbc.queryOne(adminDs, "row", "SELECT status, error_message, started_at = ended_at, resolution_exempt "
            + "FROM connector.connector_run WHERE id = ?", ps -> ps.setObject(1, run),
            rs -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3) + "|" + rs.getBoolean(4));
        assertThat(row).contains("Cancelled|cancelled by user|true|true");
        var stamp = Jdbc.queryOne(adminDs, "stamp", "SELECT e.last_run_status, e.last_run_at = r.ended_at FROM "
            + "connector.connector_endpoint e, connector.connector_run r WHERE e.id = ? AND r.id = ?",
            ps -> { ps.setObject(1, endpointId); ps.setObject(2, run); }, rs -> rs.getString(1) + "|" + rs.getBoolean(2));
        assertThat(stamp).contains("Cancelled|true");
    }

    @Test
    void a_queued_run_of_another_tenant_is_not_found() {
        seedEndpoint(2L);
        UUID run = queuedRun(2L);

        given().headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + run + "/cancel")
            .then().statusCode(404);

        assertThat(status(run)).isEqualTo("Queued");
    }

    /** The real race: the cancel blocks on the row a dispatcher holds, the dispatcher starts the run, the cancel wakes up. */
    @Test
    void a_cancel_blocked_on_the_dispatchers_row_lock_that_then_starts_is_not_reported_ended() throws Exception {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);

        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (PreparedStatement pid = holder.prepareStatement("SELECT pg_backend_pid()");
                 ResultSet rs = pid.executeQuery()) {
                rs.next();
                holderPid = rs.getInt(1);
            }
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM connector.connector_run WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, run);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).as("the dispatcher's row lock found the queued run").isTrue();
                }
            }
            CompletableFuture<Response> cancel = CompletableFuture.supplyAsync(() ->
                given().headers(TestIdentity.tenantAndUser(TENANT))
                    .when().post("/api/platform/runs/" + run + "/cancel"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (waitersOn(holderPid) < 1) {
                assertThat(System.nanoTime()).as("the cancel never reached the row lock").isLessThan(deadline);
                Thread.sleep(50);
            }
            try (PreparedStatement start = holder.prepareStatement(
                    "UPDATE connector.connector_run SET status = 'Running', started_at = now() WHERE id = ?")) {
                start.setObject(1, run);
                start.executeUpdate();
            }
            holder.commit();

            var response = cancel.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.jsonPath().getString("error")).isEqualTo("run " + run + " is not in flight on this hub");
        }
        assertThat(status(run)).isEqualTo("Running");
    }

    /** Backends queued behind the lock {@code holderPid} holds: keyed to the lock itself, not to query text. */
    /** A dispatcher that holds the row for longer than the cancel will wait: 409 "being started", the row untouched. */
    @Test
    void a_cancel_that_cannot_get_the_row_in_time_is_told_the_run_is_being_started() throws Exception {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);

        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM connector.connector_run WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, run);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            // Released from outside after 7 s: longer than the 5 s the cancel waits, so a correct door has answered by
            // then; an unbounded one is freed by this and answers 202, so the RED is a finite assertion failure.
            ScheduledExecutorService releaser = Executors.newSingleThreadScheduledExecutor();
            releaser.schedule(() -> { try { holder.rollback(); } catch (SQLException ignored) { } }, 7, TimeUnit.SECONDS);
            try {
                given().headers(TestIdentity.tenantAndUser(TENANT))
                    .when().post("/api/platform/runs/" + run + "/cancel")
                    .then().statusCode(409).body("error", containsString("being started"));
            } finally {
                releaser.shutdown();
                releaser.awaitTermination(15, TimeUnit.SECONDS);
            }
        }

        assertThat(status(run)).isEqualTo("Queued");
    }

    /** The five-second bound is for the dispatcher's row only: an endpoint row busy for longer is waited out, not reported as "being started". */
    @Test
    void a_busy_endpoint_row_delays_the_cancel_but_does_not_turn_it_into_a_being_started_409() throws Exception {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);

        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM connector.connector_endpoint WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, endpointId);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            ScheduledExecutorService releaser = Executors.newSingleThreadScheduledExecutor();
            releaser.schedule(() -> { try { holder.rollback(); } catch (SQLException ignored) { } }, 7, TimeUnit.SECONDS);
            try {
                given().headers(TestIdentity.tenantAndUser(TENANT))
                    .when().post("/api/platform/runs/" + run + "/cancel")
                    .then().statusCode(202).body("status", equalTo("cancelled"));
            } finally {
                releaser.shutdown();
                releaser.awaitTermination(15, TimeUnit.SECONDS);
            }
        }

        assertThat(status(run)).isEqualTo("Cancelled");
    }

    private long waitersOn(int holderPid) {
        return Jdbc.queryOne(adminDs, "waiters", "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
            ps -> ps.setInt(1, holderPid), rs -> rs.getLong(1)).orElse(0L);
    }
}
