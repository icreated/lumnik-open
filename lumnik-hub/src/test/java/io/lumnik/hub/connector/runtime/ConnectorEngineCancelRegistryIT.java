package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
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
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ConnectorEngine#cancel(UUID)} can answer "is this run actually in flight in this
 * JVM?" honestly, now that {@code liveRuns} is the single registry backing both questions
 * (in-flight and cancellation-requested): a random id, a null id, and an ENDED run must all be
 * refused ({@code false}) rather than politely (and wrongly) accepted.
 */
@QuarkusTest
class ConnectorEngineCancelRegistryIT {

    private static final Logger LOG = Logger.getLogger(ConnectorEngineCancelRegistryIT.class);

    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 1 — nothing in flight anywhere: a random id was never registered.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void cancel_randomId_isRefused() {
        assertThat(engine.cancel(UUID.randomUUID()))
            .as("no chunk loop in this JVM is watching a run that was never started")
            .isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 2 — a null id can never name an in-flight run.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void cancel_nullId_isRefused() {
        assertThat(engine.cancel(null)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 3 — a run that already ENDED cannot be flagged: computeIfPresent must not resurrect
    // an entry the funnel's finally-block already removed.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    void cancel_endedRun_isRefused() throws Exception {
        UUID connectorId = createSmallCsvConnector();

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status)
            .as("the fixture run must actually complete before we try to cancel it")
            .isInstanceOf(RunStatus.Completed.class);

        UUID runId = runInTxAsSystem(utx, () -> runs.findRecentActiveByConnector(connectorId, 1)).get(0).getId();

        assertThat(engine.cancel(runId))
            .as("a run whose chunk loop already exited must be refused, not flagged")
            .isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 4 — a LIVE run is flagged and actually ends Cancelled. Watchdog idiom shared with
    // ConnectorConcurrencyCursorIT:158-168 — poll the ledger for a Running run and cancel it.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @Timeout(120)
    void cancel_liveRun_endsCancelled() throws Exception {
        UUID connectorId = createSlowJdbcConnector("src_cancel_live", "src_id", 20_000, 20);

        AtomicBoolean cancelReturnedTrue = new AtomicBoolean(false);
        AtomicReference<UUID> cancelledRunId = new AtomicReference<>();

        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !cancelReturnedTrue.get()) {
                for (UUID rid : runningRunIds(connectorId)) {
                    if (engine.cancel(rid)) {
                        cancelledRunId.set(rid);
                        cancelReturnedTrue.set(true);
                        break;
                    }
                }
                try { Thread.sleep(30); } catch (InterruptedException ignored) { return; }
            }
        }, "cancel-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Future<RunStatus> future = engine.submitAsync(connectorId, new RunTrigger.Manual(1L));
        future.get(90, TimeUnit.SECONDS);
        watchdog.interrupt();

        assertThat(cancelReturnedTrue.get())
            .as("cancel() must have returned true at least once while the run was in flight")
            .isTrue();

        UUID runId = cancelledRunId.get();
        assertThat(readStatus(runId)).isEqualTo("Cancelled");
        assertThat(readErrorMessage(runId)).isEqualTo("cancelled by user");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 5 — REGRESSION GUARD (not red-first against the computeIfPresent change — it WAS
    // red-first against the Thread.interrupted() fix that ships in this same commit): interrupting
    // submitAsync's aggregating wait, while the dispatched run is genuinely mid-flight, must still
    // leave the run row in a TERMINAL status
    // (never stuck at Running) within the timeout. That is the only thing this test asserts — it
    // does NOT observe (and cannot observe, from here) whether cancel(h.runId()) inside
    // cancelOutstanding actually flagged anything: on this path the interrupt beats the cooperative
    // flag in practice (see cancelOutstanding's javadoc), so future().cancel(true) alone is what
    // drives the run to Failed via executeEndpointRun's catch. This is still a real guard — it is
    // exactly what surfaced the interrupt-poisoning bug fixed alongside this task (a still-set
    // interrupt flag left the row stuck at Running forever). (The computeIfPresent branch —
    // cancelOutstanding reaching a handle whose chunk loop has not registered in liveRuns yet — has
    // nothing observable to assert on here: the flag legitimately does not land, and the row it
    // leaves behind is the documented, accepted orphan RunReaper reaps at the next boot, not
    // something a live-JVM test can wait out.)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @Timeout(120)
    void interruptedAggregatingWait_runReachesTerminalStatus() throws Exception {
        UUID connectorId = createSlowJdbcConnector("src_cancel_guard", "src_id", 20_000, 20);

        Future<RunStatus> aggregate = engine.submitAsync(connectorId, new RunTrigger.Manual(1L));

        // Wait for real progress (several committed chunks) so the interrupt genuinely lands
        // mid-flight instead of racing a run that hasn't started yet.
        awaitChunkProgress(connectorId, 2, 20_000);

        // Interrupts the thread running submitAsync's body, which is blocked in the aggregating
        // h.future().get() — exactly the path cancelOutstanding is meant to unblock.
        aggregate.cancel(true);

        awaitAllTerminal(connectorId, 60_000);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private UUID createSmallCsvConnector() throws Exception {
        Path csvFile = Files.createTempFile("cancel_registry_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        Files.writeString(csvFile, "row_id,label\n1,a\n2,b\n");
        String filePath = csvFile.toAbsolutePath().toString();

        execSql(adminDs, "DROP TABLE IF EXISTS connector.t_cancel_registry_csv");
        execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = 't_cancel_registry_csv'");

        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("cancel-registry-csv-" + System.nanoTime());
            c.setTypeId("csv-file");
            c.setConfig(Map.of(
                "kind", "csv-file",
                "transport", Map.of("kind", "local", "path", filePath),
                "parser",    Map.of("has_header", true)
            ));
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath(filePath);
            ep.setEnabled(true);
            ep.setConfig(Map.of("name", "cancel_registry_csv"));
            endpoints.persist(ep);
            endpoints.flush();

            return c.getId();
        });
    }

    /** A JDBC full-refresh connector over a fresh source table, sized to stay Running for a while. */
    private UUID createSlowJdbcConnector(String tableName, String pkCol, int rows, int chunkSize) throws Exception {
        execSql(adminDs, "DROP TABLE IF EXISTS public." + tableName);
        execSql(adminDs, "CREATE TABLE public." + tableName + " (" + pkCol + " bigint primary key, label text)");
        execSql(adminDs, "INSERT INTO public." + tableName + " (" + pkCol + ", label) "
                + "SELECT g, 'row-' || g FROM generate_series(1, " + rows + ") g");
        execSql(adminDs, "GRANT SELECT ON public." + tableName + " TO " + DB_USER);

        String jdbcUrl;
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        final String url = jdbcUrl;

        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("cancel-registry-" + tableName + "-" + System.nanoTime());
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

    // ── polling helpers ──────────────────────────────────────────────────────────

    private List<UUID> runningRunIds(UUID connectorId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM connector.connector_run WHERE connector_id = '" + connectorId
                 + "' AND status = 'Running'")) {
            while (rs.next()) ids.add(UUID.fromString(rs.getString("id")));
        } catch (Exception e) {
            // Best-effort polling (called from a tight watchdog loop) — one failed poll must not
            // abort the watchdog. But swallowing it silently turns a broken query into a slow,
            // misleading 120s timeout instead of a fast, truthful failure — so it is logged, loudly,
            // on every occurrence.
            LOG.errorf(e, "runningRunIds poll failed for connector %s", connectorId);
        }
        return ids;
    }

    private String readStatus(UUID runId) {
        return readColumn(runId, "status");
    }

    private String readErrorMessage(UUID runId) {
        return readColumn(runId, "error_message");
    }

    private String readColumn(UUID runId, String column) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT " + column + " FROM connector.connector_run WHERE id = '" + runId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void awaitChunkProgress(UUID connectorId, int minChunks, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long chunks = scalar(adminDs, "SELECT COALESCE(MAX(chunk_count),0) FROM connector.connector_run "
                    + "WHERE connector_id = '" + connectorId + "'");
            if (chunks >= minChunks) return;
            Thread.sleep(20);
        }
        throw new AssertionError("connector " + connectorId + " never reached " + minChunks
                + " committed chunks within " + timeoutMs + "ms");
    }

    private void awaitAllTerminal(UUID connectorId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long total = scalar(adminDs, "SELECT COUNT(*) FROM connector.connector_run "
                    + "WHERE connector_id = '" + connectorId + "'");
            long running = scalar(adminDs, "SELECT COUNT(*) FROM connector.connector_run "
                    + "WHERE connector_id = '" + connectorId + "' AND status = 'Running'");
            if (total > 0 && running == 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("connector " + connectorId + " still has a Running run row after "
                + timeoutMs + "ms — the interrupted aggregating wait failed to stop it");
    }
}
