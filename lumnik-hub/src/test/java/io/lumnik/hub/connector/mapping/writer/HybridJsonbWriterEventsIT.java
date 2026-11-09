package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.event.RowEventGate;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hybrid writer stages {@code data.row.changed} for the rows it wrote or changed (#1816). A notification rule
 * narrowed to the test's table switches the gate on: it needs no existing table, unlike a Process. The tenant is
 * this class's own, so no other test's subscription reaches it and none of its rules reaches another test.
 */
@QuarkusTest
class HybridJsonbWriterEventsIT {

    private static final long TENANT = 1816L;
    private static final String RECIPIENT = "it@hybrid-events.local";
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject HybridJsonbWriter writer;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @AfterEach
    void dropRules() {
        execSql(adminDs, "DELETE FROM core.notification_rule WHERE recipient = '" + RECIPIENT + "'");
    }

    @Test
    void newRowStagesOneInsertEvent() {
        String table = table();
        subscribe(table);
        UUID runId = UUID.randomUUID();

        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", runId)));
        write(spec(table), List.of(item("B", 20)), List.of(sys("hb", null)));

        List<Event> events = events(table);
        assertEquals(2, events.size());
        // The whole payload: the row's id as a JSON string (#1816), and never its external_id.
        assertEquals(new Event("h1", CONNECTOR.toString(), Map.of("entity", table, "entity_key", "h1", "op", "insert",
            "run_id", runId.toString(), "row_id", idOf(table, "A"))), events.get(0));
        assertEquals(Map.of("entity", table, "entity_key", "hb", "op", "insert", "row_id", idOf(table, "B")),
            events.get(1).payload(), "no run_id when the context carries none");
    }

    @Test
    void changedRowStagesOneUpdateEvent() {
        String table = table();
        subscribe(table);

        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null)));
        write(spec(table), List.of(item("A", 11)), List.of(sys("h2", null)));

        List<Event> events = events(table);
        assertEquals(2, events.size());
        assertEquals(Map.of("entity", table, "entity_key", "h2", "op", "update", "row_id", idOf(table, "A")),
            events.get(1).payload(), "an update names the same row");
    }

    @Test
    void unchangedRowStagesNothing() {
        String table = table();
        subscribe(table);

        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null)));
        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null)));

        assertEquals(1, events(table).size());
    }

    @Test
    void sameHashMovedPromoteColumnStagesNothing() {
        String table = table();
        subscribe(table);
        HybridJsonbSpec wider = new HybridJsonbSpec(table, "$.id", List.of(
            new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty()),
            new PromotedColumn("currency", "$.currency", SqlType.TEXT, Optional.empty())));

        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null)));
        write(wider, List.of(item("A", 10)), List.of(sys("h1", null)));

        assertEquals("usd", scalarText("SELECT currency FROM " + table + " WHERE external_id = 'A'"),
            "the row is rewritten with the newly promoted column");
        assertEquals(1, events(table).size(), "the source did not change: no event");
    }

    @Test
    void nullHashStagesNothing() {
        String table = table();
        subscribe(table);

        write(spec(table), List.of(item("A", 10)), null);

        assertEquals(1, scalar(adminDs, "SELECT count(*) FROM " + table + " WHERE external_id = 'A'"));
        assertEquals(0, events(table).size());
    }

    @Test
    void repeatedKeyInOneChunkStagesOneEvent() {
        String table = table();
        subscribe(table);

        long unreadable = write(spec(table),
            List.of(Map.of("id", "A", "amount", "not a number", "v", 1),
                    Map.of("id", "A", "amount", "nor this", "v", 2)),
            List.of(sys("a1", null), sys("a2", null)));

        assertEquals(2, unreadable, "both items are counted, the shadowed one too");
        assertEquals("a2", scalarText("SELECT row_hash FROM " + table + " WHERE external_id = 'A'"));
        assertEquals("2", scalarText("SELECT data->>'v' FROM " + table + " WHERE external_id = 'A'"));
        List<Event> events = events(table);
        assertEquals(1, events.size());
        assertEquals("a2", events.getFirst().entityKey());
    }

    /**
     * #1819: a chunk spans several statements. Over the boundaries of a sub-batch the contract holds row by row: an
     * event iff the row is new or its hash moved, a repeated key runs once (its last version), and every item is
     * counted unreadable, the shadowed ones too.
     */
    @Test
    void aChunkOverSeveralSubBatchesStagesOneEventPerNewOrMovedRow() {
        String table = table();
        subscribe(table);
        int sub = DynamicTableWriter.subBatchSize(spec(table).promote().size() + HybridJsonbWriter.FIXED_PARAMS);
        int n = 2 * sub + 200;
        int before = sub - 1, after = sub + 1;   // either side of the first boundary
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> sys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(item("K" + i, i));
            sys.add(sys("h" + i, null));
        }
        write(spec(table), items, sys);
        assertEquals(n, events(table).size(), "every row of the first chunk is new");

        items = new ArrayList<>();
        sys = new ArrayList<>();
        int moved = 0;
        for (int i = 0; i < n; i++) {
            boolean moves = i % 3 == 1;
            if (moves && i != before && i != after) moved++;
            items.add(item("K" + i, moves ? i + 1 : i));
            sys.add(sys(moves ? "m" + i : "h" + i, null));
        }
        // The keys on both sides of a sub-batch boundary again: only the last of each runs.
        items.add(Map.of("id", "K" + before, "amount", "not a number"));
        sys.add(sys("last" + before, null));
        items.add(Map.of("id", "K" + after, "amount", 5000));
        sys.add(sys("last" + after, null));

        long unreadable = write(spec(table), items, sys);

        assertEquals(1, unreadable, "the unreadable amount of the repeated K" + before + " is counted");
        List<Event> events = events(table);
        // The two repeated keys are not in `moved`: their last version moves them, one event each.
        assertEquals(n + moved + 2, events.size());
        assertEquals("last" + before, scalarText("SELECT row_hash FROM " + table + " WHERE external_id = 'K" + before + "'"));
        assertEquals("5000", scalarText("SELECT amount::text FROM " + table + " WHERE external_id = 'K" + after + "'"));
        assertThat(events.stream().skip(n).map(Event::entityKey))
            .as("one update per moved key, the repeated ones once, under their last hash")
            .contains("last" + before, "last" + after, "m1").doesNotContain("h" + before, "h" + after);
    }

    @Test
    void noSubscriptionStagesNothing() {
        String table = table();

        long unreadable = write(spec(table), List.of(item("A", 10), Map.of("id", "B", "amount", "x")),
            List.of(sys("h1", null), sys("hb", null)));

        assertEquals(1, unreadable);
        assertEquals(2, scalar(adminDs, "SELECT count(*) FROM " + table));
        assertEquals("h1", scalarText("SELECT row_hash FROM " + table + " WHERE external_id = 'A'"));
        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM core.event_outbox WHERE tenant_id = " + TENANT
            + " AND entity = '" + table + "'"));
    }

    @Test
    void failedOutboxRollsBackTheChunk() {
        String table = table();
        subscribe(table);
        String check = "it_1816_refuse_" + System.nanoTime();
        execSql(adminDs, "ALTER TABLE core.event_outbox ADD CONSTRAINT " + check
            + " CHECK (entity IS DISTINCT FROM '" + table + "') NOT VALID");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                write(spec(table), List.of(item("A", 10), item("B", 20)), List.of(sys("h1", null), sys("hb", null))));
            assertEquals("23514", Jdbc.sqlStateOf(failure), "the outbox's check refused the event");
            // The cause is what the writer threw: its message is the operator's, and the SQLSTATE's names no table.
            assertThat(failure.getCause().getMessage()).as("the failure names its table").contains(table);
        } finally {
            execSql(adminDs, "ALTER TABLE core.event_outbox DROP CONSTRAINT IF EXISTS " + check);
        }
        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM " + table), "the data rows share the outbox's failure");
    }

    @Test
    void schemalessTableUsesPublicInTheEvent() {
        String bare = "ph_t_" + System.nanoTime();
        // The rule's filter is the gate's answer: an event proves the gate was asked with public.<table>.
        subscribe("public." + bare);

        write(new HybridJsonbSpec(bare, "$.id", List.of()), List.of(item("A", 10)), List.of(sys("h1", null)));

        List<Event> events = events("public." + bare);
        assertEquals(1, events.size());
        assertEquals("public." + bare, events.getFirst().payload().get("entity"));
    }

    @Test
    void eventWritesOfOneTableAndTenantWaitForEachOther() throws Exception {
        String table = table();
        subscribe(table);
        Connection holder = holdRowEventsLock(table);
        CompletableFuture<Long> waiting;
        try {
            waiting = CompletableFuture.supplyAsync(() ->
                write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null))));
            awaitWaiterOn(holder);
            assertFalse(waiting.isDone(), "the write waits while another event write of its table and tenant runs");
        } finally {
            release(holder);
        }
        waiting.get(10, TimeUnit.SECONDS);
        List<Event> events = events(table);
        assertEquals(1, events.size(), "released, the write stages its event");
        assertEquals("insert", events.getFirst().payload().get("op"));
    }

    @Test
    void aWriteWithoutSubscriptionDoesNotWait() throws Exception {
        String table = table();
        Connection holder = holdRowEventsLock(table);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null))));
        } finally {
            release(holder);
        }
        assertEquals(1, scalar(adminDs, "SELECT count(*) FROM " + table));
    }

    @Test
    void concurrentWritersOfOneKeyStageOneEventPerChange() throws Exception {
        String table = table();
        subscribe(table);
        write(spec(table), List.of(item("A", 10)), List.of(sys("h1", null)));
        // The oracle: a trigger logs every version of the row in the order it was written.
        String log = "public.it_1816_log_" + System.nanoTime();
        String logger = log + "_fn";
        execSql(adminDs, "CREATE TABLE " + log + " (seq BIGSERIAL, hash TEXT)");
        execSql(adminDs, "CREATE FUNCTION " + logger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "INSERT INTO " + log + " (hash) VALUES (NEW.row_hash); RETURN NULL; END $$");
        execSql(adminDs, "CREATE TRIGGER it_1816_log AFTER UPDATE ON " + table
            + " FOR EACH ROW EXECUTE FUNCTION " + logger + "()");
        try {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> alternate(table, 0));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> alternate(table, 1));
            CompletableFuture.allOf(first, second).get(120, TimeUnit.SECONDS);

            long changes = scalar(adminDs, "SELECT count(*) FROM " + log);
            assertTrue(changes > 0, "the writers changed the row");
            assertEquals(1 + changes, events(table).size(), "one insert, then one event per change of the hash");
        } finally {
            execSql(adminDs, "DROP TABLE IF EXISTS " + log);
            execSql(adminDs, "DROP TRIGGER IF EXISTS it_1816_log ON " + table);
            execSql(adminDs, "DROP FUNCTION IF EXISTS " + logger + "()");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** One staged event of the queried table: its {@code entity} is the query's filter. */
    private record Event(String entityKey, String source, Map<String, Object> payload) {}

    private static String table() {
        return "ext.ev_" + System.nanoTime();
    }

    private static HybridJsonbSpec spec(String table) {
        return new HybridJsonbSpec(table, "$.id", List.of(
            new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));
    }

    private static Map<String, Object> item(String id, int amount) {
        return Map.of("id", id, "amount", amount, "currency", "usd");
    }

    private static Map<String, Object> sys(String hash, UUID runId) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("_row_hash", hash);
        sys.put("_run_id", runId);
        return sys;
    }

    private long write(HybridJsonbSpec spec, List<Map<String, Object>> items, List<Map<String, Object>> sys) {
        return runInTxAsSystem(utx, () -> writer.write(spec, items, sys, TENANT, CONNECTOR, ENDPOINT));
    }

    /** 200 writes of key A, its hash moving between h1 and h2; {@code phase} says which comes first. */
    private void alternate(String table, int phase) {
        for (int i = 0; i < 200; i++) {
            boolean one = (i + phase) % 2 == 0;
            write(spec(table), List.of(item("A", one ? 10 : 20)), List.of(sys(one ? "h1" : "h2", null)));
        }
    }

    /** A transaction that holds the lock event writes of {@code table} take for this class's tenant. */
    private Connection holdRowEventsLock(String table) throws SQLException {
        Connection c = adminDs.getConnection();
        c.setAutoCommit(false);
        Jdbc.queryOne(c, "hold", "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            ps -> ps.setString(1, HybridJsonbWriter.rowEventsLockKey(table, TENANT)), rs -> true);
        return c;
    }

    private static void release(Connection holder) throws SQLException {
        try (holder) {
            holder.rollback();
            holder.setAutoCommit(true);
        }
    }

    /** Polls, up to 10 s, until another session waits on a lock {@code holder} holds. */
    private void awaitWaiterOn(Connection holder) throws InterruptedException {
        long pid = Jdbc.queryOne(holder, "pid", "SELECT pg_backend_pid()", ps -> {}, rs -> rs.getLong(1))
            .orElseThrow();
        String waiters = "SELECT count(*) FROM pg_locks w JOIN pg_locks h ON h.locktype = w.locktype"
            + " AND h.database = w.database AND h.classid = w.classid AND h.objid = w.objid"
            + " AND h.objsubid = w.objsubid"
            + " WHERE h.pid = " + pid + " AND h.granted AND h.locktype = 'advisory' AND NOT w.granted";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (scalar(adminDs, waiters) == 0) {
            assertTrue(System.nanoTime() < deadline, "no write came to wait on the lock within 10 s");
            Thread.sleep(20);
        }
    }

    private void subscribe(String table) {
        Jdbc.update(adminDs, "subscribe", "INSERT INTO core.notification_rule "
            + "(tenant_id, event_pattern, recipient, enabled, entity_filter) VALUES (?, ?, ?, TRUE, ?)", ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, RowEventGate.DATA_ROW_CHANGED);
                ps.setString(3, RECIPIENT);
                ps.setString(4, table);
            });
    }

    private List<Event> events(String table) {
        return Jdbc.query(adminDs, "events", "SELECT entity_key, source, payload::text FROM core.event_outbox "
            + "WHERE tenant_id = ? AND event_type = ? AND entity = ? ORDER BY id", ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, RowEventGate.DATA_ROW_CHANGED);
                ps.setString(3, table);
            }, rs -> new Event(rs.getString(1), rs.getString(2),
                JSON.readValue(rs.getString(3), new TypeReference<Map<String, Object>>() {})));
    }

    /** The stored row's id, as the event carries it: a string. */
    private String idOf(String table, String externalId) {
        return scalarText("SELECT id::text FROM " + table + " WHERE external_id = '" + externalId + "'");
    }

    private String scalarText(String sql) {
        return Jdbc.queryOne(adminDs, "scalar", sql, ps -> {}, rs -> rs.getString(1)).orElseThrow();
    }
}
