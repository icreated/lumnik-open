package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.event.EventOutboxRow.Position;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether the kind:Process policies have evaluated the rows a run wrote (#1351) — the barrier a
 * script needs before reading "no new decision" as "no decision", without reading the outbox.
 *
 * <p>Two marks, neither a clock (V76). The run's {@code outbox_high_water}, recorded when it ends:
 * the {@code process} consumer has read its rows once its cursor reaches it. The mark is the
 * tenant's, so the answer can come late, never early. And the run's {@code process_set}, recorded
 * when it starts: the cursor says the rows were read, not by which Processes, so the answer is about
 * the tenant's processes only while their fingerprint is the one the run started under. Only the
 * boolean leaves the hub, never an outbox id.
 */
@ApplicationScoped
public class RunEvaluation {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject ProcessManifestStore processes;

    /** What one request needs for every run it returns: two reads, whatever the page size. */
    public Snapshot snapshot(long tenantId) {
        // A plain read, not OutboxSweeper's em.find: that one takes the row lock the sweep holds.
        Position cursor = Jdbc.queryOne(adminDs, "RunEvaluation.cursor",
                "SELECT last_xact_id, last_event_id FROM core.event_consumer WHERE consumer_name = ?",
                ps -> ps.setString(1, ProcessConsumer.CONSUMER_NAME),
                rs -> new Position(rs.getLong(1), rs.getLong(2))).orElse(Position.ORIGIN);
        return new Snapshot(processes.processSet(tenantId), cursor);
    }

    /** The cursor and the run's mark are places in the outbox's reading order, (xact_id, id) — V85. */
    public record Snapshot(ProcessManifestStore.ProcessSet processes, Position cursor) {
        /**
         * {@code null} when the tenant has no enabled Process (nothing to evaluate — never claim an
         * evaluation that did not happen), when its processes changed since the run started (one may
         * never have seen the rows), or when the run predates V76; {@code false} while the run is
         * queued or writing.
         */
        public Boolean evaluated(ConnectorRun run) {
            if (!processes.anyEnabled()) return null;
            if (run.getStartedAt() == null) return false;
            if (!processes.fingerprint().equals(run.getProcessSet())) return null;
            if (run.getEndedAt() == null) return false;
            if (run.getOutboxHighWater() == null) return null;
            // A run that ended before V85 wrote its rows at xact 1, the value every older row carries.
            long xact = run.getOutboxHighWaterXact() != null ? run.getOutboxHighWaterXact() : 1L;
            return cursor.compareTo(new Position(xact, run.getOutboxHighWater())) >= 0;
        }
    }
}
