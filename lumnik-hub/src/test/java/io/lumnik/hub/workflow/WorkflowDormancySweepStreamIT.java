package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import io.lumnik.hub.testsupport.LogProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * #905: the sweep reads source and ledger as ONE stream, a page at a time. Neither side is ever held as a map
 * of every document, and the source table is not locked while the documents are reconciled — ingestion
 * issues {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS}, which queues for ACCESS EXCLUSIVE behind any reader and
 * stalls every later reader behind itself.
 *
 * <p>The corpus is larger than two pages, so the page boundary is crossed on every run.
 */
@QuarkusTest
class WorkflowDormancySweepStreamIT {

    @Inject WorkflowDormancySweep sweep;
    @Inject WorkflowManifestStore manifests;
    @InjectSpy WorkflowObservationStore observations;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final long TENANT = 7590L;
    private static final String WF = "wf-sweep-stream-it";
    private static final String TABLE = "connector.t_wf_sweep_stream_it";
    private static final int DOCS = 2_501;

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-sweep-stream-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_sweep_stream_it, column: statut, key: num }
          dormancy: { after: 1m }
          states:
            - { code: EN_COURS, initial: true, aliases: [en cours] }
            - { code: TERMINE, aliases: [fini], terminal: true }
          transitions:
            - { from: [EN_COURS], to: TERMINE }
        """;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE);
            s.execute("CREATE TABLE " + TABLE
                + " (id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, num TEXT, statut TEXT,"
                + "  _ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            s.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.workflow_transition WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT);
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) "
                + "SELECT " + TENANT + ", 'DOC-' || lpad(g::text, 5, '0'), 'en cours' "
                + "FROM generate_series(1, " + DOCS + ") g");
        }
        manifests.upsert(TENANT, new WorkflowManifestParser().parse(YAML), YAML, null);
    }

    /** A row left behind is swept by every later test of the gate, and this corpus is 2,500 documents. */
    @AfterEach
    void leaveNothingBehind() throws Exception {
        manifests.delete(TENANT, WF);
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE);
            s.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.workflow_transition WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT);
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void every_document_is_reconciled_across_pages_and_the_sweep_never_asks_for_the_whole_ledger() throws Exception {
        sweep.sweepNow();   // cold start: every document is new
        assertThat(scalar("SELECT COUNT(*) FROM core.workflow_observation WHERE tenant_id = " + TENANT))
            .isEqualTo(DOCS);

        // one document moves; the page it falls on is the planner's choice, and the count below
        // proves no page lost a row
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES ("
                + TENANT + ", 'DOC-" + String.format("%05d", DOCS) + "', 'fini')");
        }
        sweep.sweepNow();

        assertThat(scalar("SELECT COUNT(*) FROM core.workflow_transition WHERE tenant_id = " + TENANT))
            .as("exactly the one document that moved").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM core.workflow_observation WHERE tenant_id = " + TENANT))
            .isEqualTo(DOCS);
        verify(observations, never()).loadAll(anyLong(), anyString());
    }

    @Test
    void ingestion_can_alter_the_source_table_while_the_sweep_is_between_two_documents() throws Exception {
        AtomicReference<String> outcome = new AtomicReference<>();
        doAnswer(inv -> {
            if (outcome.get() == null) outcome.set(alterWithTimeout());
            return inv.callRealMethod();
        }).when(observations).insertNew(eq(TENANT), eq(WF), anyString(), anyString(), any());

        sweep.sweepNow();

        assertThat(outcome.get()).as("ingestion's ALTER TABLE was not made to wait on the sweep").isEqualTo("ok");
    }

    /**
     * #492 through the stream: a dropped source table is a SQLSTATE raised by the DECLARE, and it must still
     * reach the stand-down as 42P01 — said once, not logged as an ERROR on every tick.
     */
    @Test
    void a_dropped_source_table_still_stands_the_sweep_down() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE " + TABLE);
        }

        List<LogProbe.Line> lines = LogProbe.capture(WorkflowDormancySweep.class, sweep::sweepNow).stream()
            .filter(l -> l.message().contains(WF)).toList();

        assertThat(lines).as("one line for this workflow: %s", lines).hasSize(1);
        assertThat(lines.get(0).message()).contains("standing down").contains("42P01");
    }

    /** What DynamicTableWriter does to a table that gains a column, with a short fuse on the lock queue. */
    private String alterWithTimeout() {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET lock_timeout = '1500ms'");
            try {
                s.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS probe TEXT");
                return "ok";
            } finally {
                s.execute("RESET lock_timeout");
            }
        } catch (SQLException e) {
            return e.getSQLState() + " " + e.getMessage();
        }
    }
}
