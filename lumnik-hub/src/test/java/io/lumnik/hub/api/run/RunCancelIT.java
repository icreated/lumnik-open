package io.lumnik.hub.api.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@code POST /api/platform/runs/{id}/cancel} — the door onto {@link ConnectorEngine#cancel(UUID)}.
 * Four refusals/acceptances, in the order the resource checks them: tenant boundary (404), terminal
 * status (409), not-in-flight-on-this-hub (409), and the accepted request (202) — plus the lost-race
 * case where the run ends between the resource's own Running read and its call to {@code cancel()}.
 */
@QuarkusTest
class RunCancelIT {

    private static final long TENANT = 1L;
    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

    // A spy, not a plain @Inject: the lost-race test below stubs cancel() for one specific run id
    // while every other call (including this class's own live-run test) falls through to the real
    // ConnectorEngine bean — Quarkus installs this same spy instance app-wide, so RunAdminResource's
    // own injected engine field sees the stub too.
    @InjectSpy ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    // ─────────────────────────────────────────────────────────────────────────────
    // Case 4 (order in resource) — a live run: 202, then the row actually reaches Cancelled.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @Timeout(120)
    void liveRun_isAccepted_andEndsCancelled() throws Exception {
        UUID connectorId = createSlowJdbcConnector("src_run_cancel_door", "src_id", 20_000, 20);

        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicReference<UUID> acceptedRunId = new AtomicReference<>();
        AtomicReference<Throwable> watchdogFailure = new AtomicReference<>();

        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !accepted.get()) {
                try {
                    for (UUID rid : runningRunIds(connectorId)) {
                        var response = given()
                            .headers(TestIdentity.tenantAndUser(TENANT))
                            .when().post("/api/platform/runs/" + rid + "/cancel");
                        if (response.statusCode() == 202) {
                            response.then()
                                .body("status", equalTo("cancellation requested"))
                                .body("runId", equalTo(rid.toString()));
                            acceptedRunId.set(rid);
                            accepted.set(true);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    // A wrong body (or any other assertion failure) must surface as ITS OWN failure,
                    // not be swallowed by this daemon thread and misreported later as "202 never
                    // arrived" — see finding 4.
                    watchdogFailure.set(t);
                    accepted.set(true);
                    return;
                }
                try { Thread.sleep(30); } catch (InterruptedException ignored) { return; }
            }
        }, "run-cancel-door-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Future<RunStatus> future = engine.submitAsync(connectorId, new RunTrigger.Manual(TENANT));
        future.get(90, TimeUnit.SECONDS);
        watchdog.interrupt();

        if (watchdogFailure.get() != null) {
            throw new AssertionError("watchdog thread failed", watchdogFailure.get());
        }
        assertThat(accepted.get())
            .as("the door must have answered 202 while a chunk loop was actually watching the run")
            .isTrue();
        assertThat(readStatus(acceptedRunId.get())).isEqualTo("Cancelled");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Case 2 — a terminal run: 409 naming the status, row untouched.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void terminalRun_is409_andRowUnchanged() throws Exception {
        UUID runId = seedRun(TENANT, "Completed");

        given()
            .headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + runId + "/cancel")
            .then().statusCode(409)
                .body("error", equalTo("run " + runId + " already ended (Completed)"));

        assertThat(readStatus(runId)).isEqualTo("Completed");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Case 3 — an orphan Running row no chunk loop in this JVM is watching: 409, row untouched.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void orphanRunningRow_is409_notInFlight() throws Exception {
        UUID runId = seedRun(TENANT, "Running");

        given()
            .headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + runId + "/cancel")
            .then().statusCode(409)
                .body("error", equalTo("run " + runId + " is not in flight on this hub"));

        assertThat(readStatus(runId)).isEqualTo("Running");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Case 1 — another tenant's run: 404, row untouched. Checked first in the resource, ahead of
    // the terminal-status check, so a Running row (not merely a terminal one) proves the boundary.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void otherTenantRun_is404_andRowUnchanged() throws Exception {
        UUID runId = seedRun(2L, "Running");

        given()
            .headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + runId + "/cancel")
            .then().statusCode(404)
                .body("error", equalTo("run " + runId + " not found"));

        assertThat(readStatus(runId)).isEqualTo("Running");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Case 5 — the lost race: the resource's own Running read (repo.find, line ~80) sees Running,
    // but by the time it calls engine.cancel(), the run has already ended for real. The 409 must
    // name the true terminal status, not "is not in flight" — mislabeling the cause is the thing
    // this repository's honesty rule forbids. Deterministic, no sleep: engine.cancel() is stubbed
    // for this one run id to flip the row to Completed (via a raw, already-committed UPDATE on the
    // admin datasource) and THEN return false, synchronously, inside the single request thread —
    // reproducing the exact interleaving byte-for-byte instead of hoping to catch it with timing.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void raceBetweenRunningReadAndEngineCancel_answersAlreadyEnded_notNotInFlight() throws Exception {
        UUID runId = seedRun(TENANT, "Running");

        Mockito.doAnswer(invocation -> {
            updateStatus(runId, "Completed");
            return false;
        }).when(engine).cancel(eq(runId));

        given()
            .headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/platform/runs/" + runId + "/cancel")
            .then().statusCode(409)
                .body("error", equalTo("run " + runId + " already ended (Completed)"));

        assertThat(readStatus(runId)).isEqualTo("Completed");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    /** Seeds a connector for {@code tenantId} and one run at {@code status}, directly on the admin
     *  datasource — no chunk loop ever touches it, so the engine's {@code liveRuns} registry never
     *  hears about it, exactly what the orphan and terminal cases need. */
    private UUID seedRun(long tenantId, String status) throws Exception {
        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(tenantId);
            c.setName("run-cancel-door-" + tenantId + "-" + System.nanoTime());
            c.setTypeId("csv-file");
            c.setConfig(Map.of("kind", "csv-file"));
            connectors.persist(c);
            connectors.flush();

            ConnectorRun run = new ConnectorRun();
            run.setTenantId(tenantId);
            run.setConnectorId(c.getId());
            run.setStatus(status);
            run.setSyncMode("full");
            run.setTriggeredBy("Manual");
            runs.persist(run);
            runs.flush();

            return run.getId();
        });
    }

    /** A JDBC full-refresh connector over a fresh source table, sized to stay Running for a while —
     *  same fixture idiom as {@code ConnectorEngineCancelRegistryIT}. */
    private UUID createSlowJdbcConnector(String tableName, String pkCol, int rowCount, int chunkSize) throws Exception {
        execSql(adminDs, "DROP TABLE IF EXISTS public." + tableName);
        execSql(adminDs, "CREATE TABLE public." + tableName + " (" + pkCol + " bigint primary key, label text)");
        execSql(adminDs, "INSERT INTO public." + tableName + " (" + pkCol + ", label) "
                + "SELECT g, 'row-' || g FROM generate_series(1, " + rowCount + ") g");
        execSql(adminDs, "GRANT SELECT ON public." + tableName + " TO " + DB_USER);

        String jdbcUrl;
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        final String url = jdbcUrl;

        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(TENANT);
            c.setName("run-cancel-door-" + tableName + "-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setSyncMode(new SyncMode.FullRefresh());
            c.setConfig(Map.of(
                "url", url, "user", DB_USER, "password", DB_PASSWORD, "dialect", "postgres",
                "table-schema", "public", "table-name", tableName,
                "mode", "full", "pk-columns", List.of(pkCol), "chunk-size", chunkSize));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });
    }

    private List<UUID> runningRunIds(UUID connectorId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM connector.connector_run WHERE connector_id = '" + connectorId
                 + "' AND status = 'Running'")) {
            while (rs.next()) ids.add(UUID.fromString(rs.getString("id")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    private String readStatus(UUID runId) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT status FROM connector.connector_run WHERE id = '" + runId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Autocommitted, on the admin datasource — visible to any subsequent read the instant this
     *  returns, exactly like the engine's own finalizeRun() commit would be. */
    private void updateStatus(UUID runId, String status) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE connector.connector_run SET status = '" + status + "' WHERE id = '" + runId + "'");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
