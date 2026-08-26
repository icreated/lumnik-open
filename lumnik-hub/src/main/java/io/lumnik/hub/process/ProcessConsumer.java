package io.lumnik.hub.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.RowEventGate;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.view.ViewFilter;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Outbox consumer "process" — the kind:Process notify rung (É2 §9 rung 2). Sweeps
 * data.row.changed, evaluates each enabled process's when-conditions against the row content
 * (fetched by _row_hash), and on match emits the declared decision.* event into the same outbox.
 * dedup_key = process:&lt;name&gt;:&lt;triggering event id&gt; makes a cursor rewind replay-safe (the V24
 * partial unique index). Per-process fail-closed: a broken process logs and never blocks the
 * cursor (the É2 no-head-of-line rule).
 */
@ApplicationScoped
public class ProcessConsumer {

    static final String CONSUMER_NAME = "process";
    private static final Logger LOG = Logger.getLogger(ProcessConsumer.class);

    private static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, dedup_key, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW()) "
        + "ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING";

    @Inject OutboxSweeper sweeper;
    @Inject ProcessManifestStore store;
    @Inject ObjectMapper json;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    void tick() {
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER_NAME, 100, this::process));
    }

    public void process(EventOutboxRow row) {
        if (!RowEventGate.DATA_ROW_CHANGED.equals(row.getEventType())) return;
        List<ProcessManifestStore.StoredProcess> procs =
            store.findEnabledFor(row.getTenantId(), row.getEntity());
        if (procs.isEmpty()) return;

        Map<String, Object> content = fetchRow(row.getEntity(), row.getTenantId(), row.getEntityKey());
        if (content == null) {
            LOG.debugf("process consumer: row %s no longer in %s — skipped", row.getEntityKey(), row.getEntity());
            return;
        }
        for (ProcessManifestStore.StoredProcess p : procs) {
            try {
                List<ViewFilter> conds = new ArrayList<>();
                for (String w : p.when()) conds.add(ViewFilter.parse(w));
                if (ProcessRuleEvaluator.matches(content, conds)) {
                    emit(row, p, content);
                }
            } catch (RuntimeException e) {
                // fail-closed per process: never block the cursor on one broken declaration
                LOG.errorf(e, "process '%s' evaluation failed for event %d — skipped", p.name(), row.getId());
            }
        }
    }

    private Map<String, Object> fetchRow(String table, Long tenantId, String rowHash) {
        String sql = "SELECT * FROM " + quoteTable(table) + " WHERE _row_hash = ? AND tenant_id = ? LIMIT 1";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, rowHash);
            ps.setLong(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Object> out = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) out.put(md.getColumnLabel(i), rs.getObject(i));
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException("ProcessConsumer.fetchRow: " + e.getMessage(), e);
        }
    }

    private void emit(EventOutboxRow trigger, ProcessManifestStore.StoredProcess p,
                      Map<String, Object> content) {
        String payload;
        try {
            payload = buildPayload(json, p.emitMessage(), p.name(),
                trigger.getEntity(), trigger.getEntityKey(), content);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ProcessConsumer.emit: payload build failed: " + e.getMessage(), e);
        }
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(EMIT_SQL)) {
            ps.setLong(1, trigger.getTenantId());
            ps.setString(2, p.emitType());
            ps.setString(3, "process:" + p.name());
            ps.setString(4, trigger.getEntity());
            ps.setString(5, trigger.getEntityKey());
            ps.setString(6, payload);
            ps.setString(7, "process:" + p.name() + ":" + trigger.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("ProcessConsumer.emit: " + e.getMessage(), e);
        }
    }

    /** The decision event payload as valid JSON — Jackson-escaped so any message text is safe.
     *  {col} placeholders render from the row; the referenced columns ride along as `fields`
     *  (structured data for webhook consumers — n8n reads data, not text). */
    static String buildPayload(ObjectMapper json, String template, String process,
                               String entity, String entityKey, Map<String, Object> row)
            throws JsonProcessingException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", MessageTemplate.render(template, row));
        m.put("process", process);
        m.put("entity", entity);
        m.put("entity_key", entityKey);
        List<String> refs = MessageTemplate.placeholders(template);
        if (!refs.isEmpty()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String r : refs) fields.put(r, row.get(r));
            m.put("fields", fields);
        }
        return json.writeValueAsString(m);
    }

}
