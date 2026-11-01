package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.List;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Moves rows from core.audit_outbox (hot write table) to core.audit_event (partitioned log).
 * Runs every 2s; skips if previous run is still going.
 *
 * Uses the admin datasource (BYPASSRLS) because the scheduler has no tenant context:
 * the default datasource's RLS policy would fail casting an empty app.current_tenant to bigint.
 */
@ApplicationScoped
public class AuditDispatcher {

    private static final Logger LOG = Logger.getLogger(AuditDispatcher.class);
    private static final int BATCH_SIZE = 200;

    // SKIP LOCKED: a row another drain holds (a second replica, or a direct call next to the
    // scheduled one) is left to it, never copied a second time (#1311). The lock lasts until
    // drain()'s commit: the whole drain is one transaction.
    private static final String SELECT_PENDING = """
            SELECT id, tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at
              FROM core.audit_outbox
             WHERE dispatched = false
             ORDER BY created_at
             LIMIT %d
             FOR UPDATE SKIP LOCKED
            """.formatted(BATCH_SIZE);

    private static final String INSERT_EVENT = """
            INSERT INTO core.audit_event
              (tenant_id, occurred_at, actor_id, entity_type, entity_key, operation, changes)
            VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;

    private static final String MARK_DISPATCHED =
            "UPDATE core.audit_outbox SET dispatched = true WHERE id = ?";

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Scheduled(every = "2s", concurrentExecution = SKIP)
    void drain() {
        try {
            // An Error between the two batches rolls back too (#1649): left open, the pool's reset would COMMIT the
            // events without the rows marked, and the next drain would copy them again.
            int dispatched = Jdbc.transaction(adminDs, "AuditDispatcher.drain", conn -> {
                List<Row> pending = fetchPending(conn);
                if (!pending.isEmpty()) dispatch(conn, pending);
                return pending.size();
            });
            if (dispatched > 0) LOG.debugf("AuditDispatcher: dispatched %d event(s)", dispatched);
        } catch (RuntimeException e) {
            LOG.errorf(e, "AuditDispatcher: drain failed");
        }
    }

    private List<Row> fetchPending(Connection conn) {
        return Jdbc.query(conn, "AuditDispatcher.fetchPending", SELECT_PENDING, ps -> {}, rs -> new Row(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getObject("actor_id", Long.class),
                rs.getString("entity_type"),
                rs.getString("entity_key"),
                rs.getString("operation"),
                rs.getString("changes"),
                rs.getTimestamp("created_at")));
    }

    private void dispatch(Connection conn, List<Row> rows) throws SQLException {
        try (PreparedStatement ins = conn.prepareStatement(INSERT_EVENT);
             PreparedStatement upd = conn.prepareStatement(MARK_DISPATCHED)) {
            for (Row row : rows) {
                ins.setLong(1, row.tenantId());
                ins.setTimestamp(2, row.createdAt());
                ins.setObject(3, row.actorId());
                ins.setString(4, row.entityType());
                ins.setString(5, row.entityKey());
                ins.setString(6, row.operation());
                ins.setString(7, row.changesJson());
                ins.addBatch();

                upd.setLong(1, row.id());
                upd.addBatch();
            }
            ins.executeBatch();
            upd.executeBatch();
        }
    }

    private record Row(long id, long tenantId, Long actorId, String entityType,
                       String entityKey, String operation, String changesJson, Timestamp createdAt) {}
}
