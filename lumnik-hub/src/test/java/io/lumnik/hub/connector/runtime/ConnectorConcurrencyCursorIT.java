package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the fix for the concurrency runaway ([[project-jdbc-cursor-repro-test]]): the cursor
 * is one row shared by every run of a connector. The fix is two layers:
 * <ol>
 *   <li><b>Advisory lock</b> ({@code pg_try_advisory_lock} per connectorId in {@code ConnectorEngine})
 *       — only one run of a connector executes at a time across the cluster; redundant submissions skip.</li>
 *   <li><b>{@code @Version}</b> on {@code ConnectorCursorEntity} — even if two writers ever overlap,
 *       the second to commit fails optimistically instead of rewinding the cursor (lost update).</li>
 * </ol>
 *
 * <p>Before the fix these two tests were RED (cursor rewound 1000→500; a single run ingested
 * ~905,500 rows from a 3,000-row table). They now assert the corrected behaviour.
 */
@QuarkusTest
class ConnectorConcurrencyCursorIT {

    private static final int CHUNK = 500;
    private static final int ROWS = 3000;
    private static final String PK_COL = "src_conc_id";

    @Inject ConnectorRepository connectors;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject ConnectorEngine engine;
    @Inject UserTransaction utx;
    @Inject ObjectMapper mapper;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private UUID connectorId;
    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        execSql("DROP TABLE IF EXISTS public.src_conc");
        execSql("CREATE TABLE public.src_conc (" + PK_COL + " bigint primary key, label text)");
        execSql("INSERT INTO public.src_conc (" + PK_COL + ", label) "
              + "SELECT g, 'row-' || g FROM generate_series(1, " + ROWS + ") g");
        execSql("GRANT SELECT ON public.src_conc TO " + DB_USER);
        connectorId = createFullConnector();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 1 — @Version: two concurrent transactions loading the SAME cursor version
    // cannot both commit; the second hits an optimistic-lock conflict (no lost update).
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @Timeout(60)
    void optimisticVersion_rejectsConcurrentLostUpdate() throws Exception {
        // The cursor is keyed by endpoint_id (the PK). Use one synthetic endpoint id for this test.
        final UUID eid = UUID.randomUUID();
        // Seed the shared cursor row at version 0.
        EventItSupport.runInTxAsSystem(utx, () -> {
            ConnectorCursorEntity e = new ConnectorCursorEntity();
            e.setTenantId(1L);
            e.setEndpointId(eid);
            e.setConnectorId(connectorId);
            e.setCursorValue(mapper.createObjectNode().put("seed", 0));
            e.setCapturedAt(Instant.now());
            cursors.persist(e);
            cursors.flush();
            return null;
        });

        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        ConcurrentHashMap<String, String> outcome = new ConcurrentHashMap<>();

        Runnable writer = () -> {
            String name = Thread.currentThread().getName();
            var rc = Arc.container().requestContext();
            rc.activate();
            try {
                TenantContext.runAsSystem(() -> {
                    boolean committed = false;
                    try {
                        utx.begin();
                        ConnectorCursorEntity e = cursors.findById(eid);   // both load version 0
                        e.setCursorValue(mapper.createObjectNode().put("writer", name));
                        bothLoaded.await(10, TimeUnit.SECONDS);                     // overlap guaranteed
                        utx.commit();                                              // version 0 -> 1 (one wins)
                        committed = true;
                    } catch (Exception ex) {
                        try { utx.rollback(); } catch (Exception ignored) {}
                    }
                    outcome.put(name, committed ? "OK" : "CONFLICT");
                });
            } finally {
                rc.deactivate();
            }
        };

        Thread w1 = new Thread(writer, "w1");
        Thread w2 = new Thread(writer, "w2");
        w1.start(); w2.start();
        w1.join(); w2.join();

        assertThat(outcome.values())
            .as("exactly one writer commits; the other must hit an optimistic-lock conflict "
                + "(not a silent lost update / rewind): %s", outcome)
            .containsExactlyInAnyOrder("OK", "CONFLICT");

        assertThat(readVersion())
            .as("the cursor version must have advanced exactly once (no double-apply)")
            .isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 2 — Advisory lock end-to-end: N parallel runs of the SAME connector.
    // No run may exceed the table size (a runaway re-reads pages unboundedly).
    // Watchdog-bounded so a regression cannot hang the suite.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @Timeout(120)
    void concurrentRunsSameConnector_serializedByAdvisoryLock() throws Exception {
        final int N = 4;
        final long BUDGET_MS = 8_000;

        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(BUDGET_MS); } catch (InterruptedException ignored) { return; }
            long deadline = System.currentTimeMillis() + 6_000;
            while (System.currentTimeMillis() < deadline) {
                for (UUID rid : runningRunIds()) engine.cancel(rid);
                try { Thread.sleep(300); } catch (InterruptedException ignored) { return; }
            }
        }, "concurrency-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        List<Future<RunStatus>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(engine.submitAsync(connectorId, new RunTrigger.Manual(0L)));
        }
        for (Future<RunStatus> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) { /* cancelled/slow */ }
        }
        watchdog.interrupt();

        long maxIn = scalar("SELECT COALESCE(MAX(records_in),0) FROM connector.connector_run "
                + "WHERE connector_id = '" + connectorId + "'");
        long sum = scalar("SELECT COALESCE(SUM(records_in),0) FROM connector.connector_run "
                + "WHERE connector_id = '" + connectorId + "'");
        long runCount = scalar("SELECT COUNT(*) FROM connector.connector_run "
                + "WHERE connector_id = '" + connectorId + "'");

        // Non-vacuous: a run actually ingested (otherwise maxIn<=ROWS would pass trivially on
        // a 0-row run, e.g. a unified-key mismatch). Exactly one endpoint run does a clean pass.
        assertThat(maxIn)
            .as("the serialized run actually ingested rows (non-vacuous). max=%d, runs=%d", maxIn, runCount)
            .isPositive();

        assertThat(maxIn)
            .as("no single run may exceed the table size (%d) — a runaway re-reads pages without "
                + "bound (pre-fix a single run reached ~905k). max=%d, runs=%d, sum=%d",
                ROWS, maxIn, runCount, sum)
            .isLessThanOrEqualTo((long) ROWS);

        assertThat(sum)
            .as("total ingest stays bounded (≤ rows*runs): sum=%d", sum)
            .isLessThanOrEqualTo((long) ROWS * N);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID createFullConnector() {
        return EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("jdbc-conc-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setSyncMode(new SyncMode.FullRefresh());
            c.setConfig(Map.of(
                "url", jdbcUrl, "user", DB_USER, "password", DB_PASSWORD, "dialect", "postgres",
                "table-schema", "public", "table-name", "src_conc",
                "mode", "full", "pk-columns", List.of(PK_COL), "chunk-size", CHUNK));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });
    }

    private long readVersion() {
        return scalar("SELECT COALESCE(MAX(version),-1) FROM connector.connector_cursor "
                + "WHERE connector_id = '" + connectorId + "'");
    }

    private List<UUID> runningRunIds() {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id FROM connector.connector_run WHERE connector_id = '" + connectorId
                 + "' AND status = 'Running'")) {
            while (rs.next()) ids.add(UUID.fromString(rs.getString("id")));
        } catch (Exception e) { /* best effort */ }
        return ids;
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
