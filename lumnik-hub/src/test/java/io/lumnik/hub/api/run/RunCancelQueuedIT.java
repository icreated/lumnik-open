package io.lumnik.hub.api.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.runtime.ConnectorRunDispatcher;
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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** The longest a test waits for the cancel's answer: the door's bounded wait, and slack for a loaded suite. */
    private static final Duration ANSWER = ConnectorRunDispatcher.QUEUED_CANCEL_ROW_WAIT.plusSeconds(25);

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
            CompletableFuture<Response> cancel = null;
            try {
                lockRow(holder, "connector.connector_run", run);
                cancel = sendCancel(run);
                awaitQueuedBehind(holder, cancel);
                try (PreparedStatement start = holder.prepareStatement(
                        "UPDATE connector.connector_run SET status = 'Running', started_at = now() WHERE id = ?")) {
                    start.setObject(1, run);
                    start.executeUpdate();
                }
                holder.commit();

                answer(cancel).then().statusCode(409).body("error", equalTo("run " + run + " is not in flight on this hub"));
            } finally {
                try {
                    holder.rollback();
                } finally {
                    settle(cancel);
                }
            }
        }
        assertThat(status(run)).isEqualTo("Running");
    }

    /**
     * A dispatcher that holds the row for longer than the cancel will wait: 409 "being started", the row untouched.
     * The holder keeps the row until the cancel has answered (#2024): the door's own bounded wait decides, no clock of
     * the test's. An unbounded door never answers, and the RED is the bounded {@link #answer}.
     */
    @Test
    void a_cancel_that_cannot_get_the_row_in_time_is_told_the_run_is_being_started() throws Exception {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);

        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            CompletableFuture<Response> cancel = null;
            try {
                lockRow(holder, "connector.connector_run", run);
                cancel = sendCancel(run);
                awaitQueuedBehind(holder, cancel);

                answer(cancel).then().statusCode(409).body("error", containsString("being started"));
            } finally {
                try {
                    holder.rollback();
                } finally {
                    settle(cancel);
                }
            }
        }

        assertThat(status(run)).isEqualTo("Queued");
    }

    /**
     * The door's bounded wait is for the dispatcher's row only: an endpoint row busy for longer is waited out, not
     * reported as "being started". The hold is counted from a moment the cancel is already seen waiting (#2024), so it
     * outlasts that bound on the endpoint row whatever the load.
     */
    @Test
    void a_busy_endpoint_row_delays_the_cancel_but_does_not_turn_it_into_a_being_started_409() throws Exception {
        seedEndpoint(TENANT);
        UUID run = queuedRun(TENANT);

        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            CompletableFuture<Response> cancel = null;
            try {
                lockRow(holder, "connector.connector_endpoint", endpointId);
                cancel = sendCancel(run);
                awaitQueuedBehind(holder, cancel);

                Thread.sleep(ConnectorRunDispatcher.QUEUED_CANCEL_ROW_WAIT.plusSeconds(2).toMillis());
                assertThat(waitersOn(holder)).as("the cancel gave up on the endpoint row instead of waiting it out")
                    .isEqualTo(1);
                holder.rollback();

                answer(cancel).then().statusCode(202).body("status", equalTo("cancelled"));
            } finally {
                try {
                    holder.rollback();
                } finally {
                    settle(cancel);
                }
            }
        }

        assertThat(status(run)).isEqualTo("Cancelled");
    }

    /** {@code SELECT … FOR UPDATE} on one row of {@code table}, held by {@code conn}'s open transaction. */
    private static void lockRow(Connection conn, String table, UUID id) throws SQLException {
        try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, id);
            try (ResultSet locked = lock.executeQuery()) {
                assertThat(locked.next()).as("the holder's row lock found its row in " + table).isTrue();
            }
        }
    }

    private static CompletableFuture<Response> sendCancel(UUID run) {
        return CompletableFuture.supplyAsync(() ->
            given().headers(TestIdentity.tenantAndUser(TENANT)).when().post("/api/platform/runs/" + run + "/cancel"));
    }

    /** Until the cancel is queued behind {@code holder}'s lock; it must not answer before getting there. */
    private static void awaitQueuedBehind(Connection holder, CompletableFuture<Response> cancel) throws InterruptedException {
        long deadline = System.nanoTime() + ANSWER.toNanos();
        while (waitersOn(holder) < 1) {
            if (cancel.isDone()) {   // join() rethrows a request that failed, with its own cause
                throw new AssertionError("the cancel answered before it reached the row lock: " + cancel.join().statusCode());
            }
            assertThat(System.nanoTime()).as("the cancel never reached the row lock").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    /** The cancel's answer, within the door's bounded wait and slack for a loaded suite. */
    private static Response answer(CompletableFuture<Response> cancel) throws Exception {
        return cancel.get(ANSWER.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** After a failure, lets a cancel still in flight end before the clean-up deletes its rows; the test reports it. */
    private static void settle(CompletableFuture<Response> cancel) {
        if (cancel == null) return;
        try {
            cancel.get(ANSWER.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // the test's own assertion already says what went wrong
        }
    }

    /**
     * Backends queued behind a lock {@code holder} holds, asked on the holder itself. {@code pg_locks}, not
     * {@code pg_stat_activity}: inside the holder's open transaction the latter is a snapshot taken at its first read,
     * blind to a backend that connects later.
     */
    private static long waitersOn(Connection holder) {
        return Jdbc.queryOne(holder, "waiters", "SELECT count(DISTINCT pid) FROM pg_locks "
            + "WHERE NOT granted AND pg_backend_pid() = ANY(pg_blocking_pids(pid))", ps -> {}, rs -> rs.getLong(1)).orElse(0L);
    }
}
