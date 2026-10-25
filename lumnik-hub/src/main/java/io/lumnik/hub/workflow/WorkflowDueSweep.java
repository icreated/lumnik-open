package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static io.lumnik.hub.workflow.WorkflowManifestParser.norm;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * The due-date sweep (#1448): for each enabled workflow declaring {@code spec.due}, every document
 * whose latest state is one of the due states and whose due date has passed raises one
 * {@code decision.overdue.<workflow>} — once per document and deadline.
 *
 * <p>The clock is the DATA's, not lumnik's: unlike dormancy there is no cold start, a date passed
 * before the first sweep is overdue on the first sweep. A date column is due at the end of its day
 * in {@code spec.due.zone} (UTC when absent); a timestamp with a zone at its instant; one without,
 * at its wall-clock time in that zone.
 *
 * <p>The documents are read on the READONLY datasource under the tenant ({@link TenantRlsScope}),
 * because a fused view is {@code security_invoker} and carries no {@code tenant_id}: RLS on the
 * tables it fuses is what confines it. A page at a time, each in its own short transaction, so no
 * lock on the source outlives the read of one page (the reason dormancy holds a cursor). The memory
 * of what was alerted is {@code core.workflow_due_alert} (V81), written with its outbox event in one
 * transaction on the admin datasource: the outbox is the transport, it is purged after the retention.
 */
@ApplicationScoped
public class WorkflowDueSweep {

    private static final Logger LOG = Logger.getLogger(WorkflowDueSweep.class);
    private static final int PAGE = 1_000;

    private static final String TENANTS_SQL =
        "SELECT DISTINCT tenant_id FROM core.workflow_manifest WHERE enabled";
    /** A page's deadlines into the ledger at once; what comes back is what had not been alerted yet. */
    private static final String REMEMBER_SQL =
        "INSERT INTO core.workflow_due_alert (tenant_id, workflow_name, doc_key, due_at) "
        + "SELECT ?, ?, u.k, u.d FROM unnest(?::text[], ?::timestamptz[]) AS u(k, d) "
        + "ON CONFLICT DO NOTHING RETURNING doc_key, due_at";
    /** Held for the page's transaction: a delete or a disable of the workflow waits for it, and then finds
     *  the rows it wrote — no alert lands for a declaration already gone, no ledger row outlives it. */
    private static final String STILL_DECLARED_SQL =
        "SELECT 1 FROM core.workflow_manifest WHERE tenant_id = ? AND name = ? AND enabled FOR SHARE";
    private static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW(), NOW())";

    /** One overdue document: its key, its state, the instant its deadline passed, the date as the data says it. */
    record Overdue(String key, String state, Instant dueAt, String due) {}

    /** (tenant, workflow) → the condition already said, so a declaration the data does not match is said ONCE. */
    private final Map<String, String> stoodDown = new ConcurrentHashMap<>();

    @Inject WorkflowRegistry registry;
    @Inject ObjectMapper json;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @Scheduled(every = "${lumnik.workflow.sweep.every:15m}", concurrentExecution = SKIP)
    void tick() {
        sweepNow();
    }

    public void sweepNow() {
        sweepAt(Instant.now());
    }

    void sweepAt(Instant now) {
        for (long tenant : tenants()) {
            for (WorkflowGraph g : registry.allParsable(tenant)) {
                if (g.due() == null || g.key() == null) continue;
                try {
                    sweepOne(tenant, g, now);
                } catch (RuntimeException e) {
                    // fail-closed per workflow: one broken declaration never stops the others
                    LOG.errorf(e, "due sweep failed for tenant %d workflow %s — skipped", tenant, g.name());
                }
            }
        }
    }

    private void sweepOne(long tenant, WorkflowGraph g, Instant now) {
        String dataType = columnType(g.table(), g.due().column());
        DueColumn kind = DueColumn.of(dataType);
        if (kind == null) {
            standDown(tenant, g, DueColumn.problem(g.table(), g.due().column(), dataType));
            return;
        }
        if (stoodDown.remove(key(tenant, g)) != null)
            LOG.infof("due sweep resumed for tenant %d workflow %s", tenant, g.name());

        ZoneId zone = g.due().zoneId();
        String[] values = dueValues(g);
        String after = "";
        List<Overdue> page;
        do {
            String from = after;
            page = QuarkusTransaction.requiringNew().call(() -> readPage(tenant, g, kind, values, zone, now, from));
            alert(tenant, g, page);
            if (!page.isEmpty()) after = page.get(page.size() - 1).key();
        } while (page.size() == PAGE);
    }

    /** Every raw value that means one of the due states: their codes and aliases, normalized. */
    private static String[] dueValues(WorkflowGraph g) {
        Set<String> out = new LinkedHashSet<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            if (!g.due().states().contains(s.code())) continue;
            out.add(norm(s.code()));
            s.aliases().forEach(a -> out.add(norm(a)));
        }
        return out.toArray(String[]::new);
    }

    /**
     * One page of overdue documents, keyed after {@code after}. An ingest table keeps a document's
     * versions, so its latest one is picked first, the way dormancy does; a fused view has one row
     * per key already. The state and the date are filtered on that latest version, never before.
     */
    private List<Overdue> readPage(long tenant, WorkflowGraph g, DueColumn kind, String[] values, ZoneId zone,
                                   Instant now, String after) throws SQLException {
        String key = quoteIdent(g.key());
        // The page bound sits inside, on the key every version shares: each later page sorts less.
        String select = key + "::text AS k, " + quoteIdent(g.column()) + "::text AS v, "
            + quoteIdent(g.due().column()) + " AS d FROM " + quoteTable(g.table())
            + " WHERE " + key + " IS NOT NULL AND btrim(" + key + "::text) <> '' AND " + key + "::text > ?";
        // The tiebreaker is dormancy's, for its reason (WorkflowDormancySweep.declareStream): one ingest
        // chunk shares one _ingested_at, so without `id DESC` the latest version is picked arbitrarily.
        String documents = g.table().startsWith("connector.t_")
            ? "SELECT DISTINCT ON (" + key + ") " + select + " ORDER BY " + key + ", _ingested_at DESC, id DESC"
            : "SELECT " + select;
        String sql = "SELECT s.k, s.v, s.d FROM (" + documents + ") s "
            + "WHERE lower(btrim(s.v)) = ANY (?) AND s.d " + (kind == DueColumn.DATE ? "<" : "<=") + " ? "
            + "ORDER BY s.k LIMIT " + PAGE;
        List<Overdue> out = new ArrayList<>();
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenant);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, after);
                ps.setArray(2, c.createArrayOf("text", values));
                switch (kind) {
                    case DATE -> ps.setObject(3, LocalDate.ofInstant(now, zone));
                    case TIMESTAMPTZ -> ps.setObject(3, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                    case TIMESTAMP -> ps.setObject(3, LocalDateTime.ofInstant(now, zone));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(overdue(rs, kind, zone, g));
                }
            }
        }
        return out;
    }

    private static Overdue overdue(ResultSet rs, DueColumn kind, ZoneId zone, WorkflowGraph g) throws SQLException {
        String key = rs.getString(1);
        String state = WorkflowDormancySweep.resolve(g, rs.getString(2));
        return switch (kind) {
            case DATE -> {
                LocalDate d = rs.getObject(3, LocalDate.class);
                yield new Overdue(key, state, endOfDay(d, zone), d.toString());
            }
            case TIMESTAMPTZ -> {
                Instant at = rs.getObject(3, OffsetDateTime.class).toInstant();
                yield new Overdue(key, state, at, at.toString());
            }
            case TIMESTAMP -> {
                LocalDateTime t = rs.getObject(3, LocalDateTime.class);
                yield new Overdue(key, state, t.atZone(zone).toInstant(), t.toString());
            }
        };
    }

    /** A date is due at the end of its day, where its readers work: the start of the next day in {@code zone}. */
    static Instant endOfDay(LocalDate d, ZoneId zone) {
        return d.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /**
     * The ledger rows and the events, in one transaction per page: an alert the ledger remembers was
     * sent, or neither. A deadline already in the ledger — the same date again, or a date moved back to
     * one already alerted — does not come back from the insert and writes nothing, which is the steady
     * state of an overdue backlog: one statement per page, not one transaction per document. A page
     * that fails is retried one document at a time, so one poison row is logged and skipped, never the page.
     */
    private void alert(long tenant, WorkflowGraph g, List<Overdue> docs) {
        if (docs.isEmpty()) return;
        try {
            Jdbc.transaction(adminDs, "WorkflowDueSweep.alert", c -> {
                if (!Jdbc.exists(c, "WorkflowDueSweep.stillDeclared", STILL_DECLARED_SQL, ps -> {
                    ps.setLong(1, tenant);
                    ps.setString(2, g.name());
                })) return;
                Set<String> fresh = new HashSet<>(Jdbc.query(c, "WorkflowDueSweep.remember", REMEMBER_SQL, ps -> {
                    ps.setLong(1, tenant);
                    ps.setString(2, g.name());
                    ps.setArray(3, c.createArrayOf("text", docs.stream().map(Overdue::key).toArray()));
                    ps.setArray(4, c.createArrayOf("timestamptz",
                        docs.stream().map(o -> Timestamp.from(o.dueAt())).toArray()));
                }, rs -> deadline(rs.getString(1), Jdbc.instant(rs, 2))));
                for (Overdue o : docs) if (fresh.contains(deadline(o.key(), o.dueAt()))) emit(c, tenant, g, o);
            });
        } catch (RuntimeException e) {
            if (docs.size() > 1) {
                docs.forEach(o -> alert(tenant, g, List.of(o)));
                return;
            }
            LOG.warnf(e, "due: document skipped (tenant %d, workflow %s, key %s)", tenant, g.name(), docs.get(0).key());
        }
    }

    private static String deadline(String key, Instant dueAt) {
        return key + '\0' + dueAt.toEpochMilli();
    }

    private void emit(Connection c, long tenant, WorkflowGraph g, Overdue o) throws Exception {
        String state = o.state();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("message", DueMessage.render(g, o.key(), state, o.due()));
        p.put("workflow", g.name());
        p.put("doc_key", o.key());
        p.put("state", state);
        p.put("due", o.due());
        p.put("due_at", o.dueAt().toString());
        String payload = json.writeValueAsString(p);
        Jdbc.update(c, "WorkflowDueSweep.emit", EMIT_SQL, ps -> {
            ps.setLong(1, tenant);
            ps.setString(2, "decision.overdue." + g.name());
            ps.setString(3, "workflow:" + g.name());
            ps.setString(4, g.table());
            ps.setString(5, WorkflowDormancySweep.bounded(o.key()));
            ps.setString(6, payload);
        });
    }

    /** The column's type as the catalog names it, null when it is not there — as the readonly role, which reads it. */
    private String columnType(String table, String column) {
        try (Connection c = readonlyDs.getConnection()) {
            return WorkflowDataTruth.columnType(c, table, column);
        } catch (SQLException e) {
            throw new RuntimeException("WorkflowDueSweep.columnType: " + e.getMessage(), e);
        }
    }

    /** A declaration the data does not match is a state, not an incident: said once until it changes. */
    private void standDown(long tenant, WorkflowGraph g, String why) {
        if (why.equals(stoodDown.put(key(tenant, g), why))) return;
        LOG.warnf("due sweep standing down for tenant %d workflow %s: %s (%s.%s). Nothing further will be"
            + " logged for it until the column is a date again.", tenant, g.name(), why, g.table(), g.due().column());
    }

    private static String key(long tenant, WorkflowGraph g) {
        return tenant + ":" + g.name();
    }

    private List<Long> tenants() {
        return Jdbc.query(adminDs, "WorkflowDueSweep.tenants", TENANTS_SQL, ps -> {}, rs -> rs.getLong(1));
    }
}
