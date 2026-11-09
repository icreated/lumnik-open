package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.event.OutboxKey;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.Date;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    static final String STILL_DECLARED_SQL =
        "SELECT 1 FROM core.workflow_manifest WHERE tenant_id = ? AND name = ? AND enabled FOR SHARE";
    static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW(), NOW())";

    /** One overdue document: its key, its state, the instant its deadline passed, the date as the data says it, and
     *  the cited and declared columns of the version it is about (#1588). */
    record Overdue(String key, String state, Instant dueAt, String due, Map<String, Object> context) {

        Overdue with(Map<String, Object> context) {
            return new Overdue(key, state, dueAt, due, context);
        }
    }

    /** One page of a table: its overdue documents, the keys it could not read, and where the next page starts. */
    private record Page(List<Overdue> overdue, List<String> unreadable, String lastKey, int rows) {}

    /** What has already been said, so a declaration the data does not match is said ONCE ({@link WorkflowConditions}). */
    private final WorkflowConditions conditions = new WorkflowConditions(LOG, "due");

    private volatile Runnable beforeRead = () -> {};

    @Inject WorkflowRegistry registry;
    @Inject DynamicTableRegistry tables;
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

    /** Runs after the catalog is read and before the statement: an IT drops a column there. */
    void beforeRead(Runnable r) {
        beforeRead = r;
    }

    void sweepAt(Instant now) {
        Set<String> swept = new HashSet<>();
        for (long tenant : tenants()) {
            for (WorkflowGraph g : registry.allParsable(tenant)) {
                if (g.due() == null || g.key() == null) continue;
                swept.add(WorkflowConditions.workflowOf(tenant, g.name()));
                try {
                    CitedColumns.rebuiltOnce(() -> {
                        sweepOne(tenant, g, now);
                        return null;
                    });
                } catch (RuntimeException e) {
                    // fail-closed per workflow: one broken declaration never stops the others
                    LOG.errorf(e, "due sweep failed for tenant %d workflow %s — skipped", tenant, g.name());
                }
            }
        }
        conditions.forgetUnswept(swept);
    }

    private void sweepOne(long tenant, WorkflowGraph g, Instant now) {
        String dataType = columnType(g.table(), g.due().column());
        DueColumn kind = DueColumn.of(dataType);
        if (kind == null) {
            conditions.standDown(tenant, g.name(), WorkflowConditions.DUE_COLUMN,
                DueColumn.problem(g.table(), g.due().column(), dataType),
                "Nothing further will be logged for it until the column is a date again.");
            return;
        }
        conditions.resumed(tenant, g.name(), WorkflowConditions.DUE_COLUMN, g.due().column() + " is a date again");

        OrderColumn order = null;
        if (g.order() == null) {
            conditions.releaseOrder(tenant, g.name());
        } else {
            // The due column was just found, so the relation is there: only an order that cannot order stops here.
            if (!(OrderColumn.forSweep(readonlyDs, conditions, tenant, g) instanceof OrderColumn.Orders o)) return;
            order = o.kind();
        }
        ZoneId zone = g.due().zoneId();
        CitedColumns context = CitedColumns.of(readonlyDs, conditions, tenant, g, g.due().columns());
        beforeRead.run();
        if (order != null && g.onFusedView()) {
            sweepView(tenant, g, kind, order, context, zone, now);
            return;
        }
        String[] values = dueValues(g);
        // A fused view without order follows the row-by-row path below and never uses this column.
        String writtenAt = g.onFusedView() ? null : tables.writtenAtColumn(tenant, g.table());
        OrderColumn ordered = order;
        KeySample unreadable = new KeySample();
        String after = "";
        Page page;
        do {
            String from = after;
            page = QuarkusTransaction.requiringNew().call(
                () -> readPage(tenant, g, kind, ordered, context, values, writtenAt, zone, now, from));
            alert(tenant, g, page.overdue());
            page.unreadable().forEach(unreadable::add);
            after = page.lastKey();
        } while (page.rows() == PAGE);
        if (order != null) {
            conditions.noteKeys(tenant, g.name(), WorkflowConditions.UNREADABLE, unreadable,
                WorkflowConditions.UNREADABLE_WHY);
            // a table breaks every tie by its ingestion: nothing on it is ambiguous, whatever a view said before
            conditions.noteKeys(tenant, g.name(), WorkflowConditions.AMBIGUOUS, new KeySample(),
                WorkflowConditions.AMBIGUOUS_WHY);
        }
    }

    /**
     * A fused view with spec.on.order (#1588): whole keys a page at a time ({@link ViewDocuments}), the deadline read
     * with the current version, and the overdue filter applied to that version here. Without an order a view keeps
     * today's reading, row by row.
     */
    private void sweepView(long tenant, WorkflowGraph g, DueColumn kind, OrderColumn order, CitedColumns context,
                           ZoneId zone, Instant now) {
        KeySample unreadable = new KeySample();
        KeySample ambiguous = new KeySample();
        List<String> extra = new ArrayList<>(List.of(quoteIdent(g.due().column())));
        extra.addAll(context.expressions());   // the deadline first, then the cited and declared columns
        ViewDocuments.forEachPage(readonlyDs, tenant, g, order, true, extra, PAGE, page -> {
            List<Overdue> late = new ArrayList<>();
            for (ViewDocuments.Document d : page) {
                // Two rows agree when they say the same state and the same deadline.
                switch (ViewDocuments.decide(d, r -> Arrays.asList(norm(WorkflowDormancySweep.resolve(g, r.raw())),
                        r.extra().get(0)))) {
                    case ViewDocuments.Current cur -> {
                        String state = WorkflowDormancySweep.resolve(g, cur.row().raw());
                        Object due = cur.row().extra().get(0);
                        if (due == null || !g.due().states().contains(state)) continue;
                        Overdue o = overdue(d.key(), state, due, kind, zone, Map.of());
                        if (!o.dueAt().isAfter(now))
                            late.add(o.with(context.read(cur.row().extra(), 1)));   // the deadline is extra[0]
                    }
                    case ViewDocuments.Unreadable u -> unreadable.add(d.key());
                    case ViewDocuments.Ambiguous a -> ambiguous.add(d.key());
                }
            }
            alert(tenant, g, late);
        });
        conditions.noteKeys(tenant, g.name(), WorkflowConditions.UNREADABLE, unreadable,
            WorkflowConditions.UNREADABLE_WHY);
        conditions.noteKeys(tenant, g.name(), WorkflowConditions.AMBIGUOUS, ambiguous, WorkflowConditions.AMBIGUOUS_WHY);
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
     * One page of documents keyed after {@code after}: the overdue ones, and those whose current version cannot be
     * told, both in one read. An ingest table keeps a document's versions, so its current one is picked first
     * ({@link OrderColumn#currentOnTable}); a fused view without an order is read row by row, as before #1588. The
     * state and the date are filtered on that current version, never before.
     */
    private Page readPage(long tenant, WorkflowGraph g, DueColumn kind, OrderColumn order, CitedColumns context,
                          String[] values, String writtenAt, ZoneId zone, Instant now, String after) throws SQLException {
        String key = quoteIdent(g.key());
        // Built from its parts, never by rewriting its own text: a column name is the source's, free, and may hold
        // " FROM " inside its quotes — a String.replace on the statement would rewrite the name, or break out of it.
        String columns = key + "::text AS k, " + quoteIdent(g.column()) + "::text AS v, "
            + quoteIdent(g.due().column()) + " AS d" + context.projected();
        // The page bound sits inside, on the key every version shares: each later page sorts less.
        String where = " WHERE " + key + " IS NOT NULL AND btrim(" + key + "::text) <> '' AND " + key + "::text > ?";
        String documents = g.onFusedView()
            ? "SELECT " + columns + ", false AS unreadable FROM " + quoteTable(g.table()) + where
            : OrderColumn.currentOnTable(order, order == null ? null : quoteIdent(g.order()), key, columns, g.table(),
                writtenAt, where);
        String sql = "SELECT s.k, s.v, s.d, s.unreadable" + context.carried() + " FROM (" + documents + ") s "
            + "WHERE s.unreadable OR (lower(btrim(s.v)) = ANY (?) AND s.d " + (kind == DueColumn.DATE ? "<" : "<=")
            + " ?) ORDER BY s.k LIMIT " + PAGE;
        List<Overdue> overdue = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> keys;
        try (Connection c = readonlyDs.getConnection()) {
            TenantRlsScope.applyTo(c, tenant);
            keys = Jdbc.query(c, "WorkflowDueSweep.readPage", sql, ps -> {
                ps.setString(1, after);
                ps.setArray(2, c.createArrayOf("text", values));
                switch (kind) {
                    case DATE -> ps.setObject(3, LocalDate.ofInstant(now, zone));
                    case TIMESTAMPTZ -> ps.setObject(3, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                    case TIMESTAMP -> ps.setObject(3, LocalDateTime.ofInstant(now, zone));
                }
            }, rs -> {
                String k = rs.getString(1);
                if (rs.getBoolean(4)) unreadable.add(k);
                else overdue.add(overdue(rs, kind, zone, g, context.read(rs, 5)));
                return k;
            });
        }
        String last = keys.isEmpty() ? after : keys.getLast();
        return new Page(overdue, unreadable, last, keys.size());
    }

    private static Overdue overdue(ResultSet rs, DueColumn kind, ZoneId zone, WorkflowGraph g,
                                   Map<String, Object> context) throws SQLException {
        Object due = switch (kind) {
            case DATE -> rs.getObject(3, LocalDate.class);
            case TIMESTAMPTZ -> rs.getObject(3, OffsetDateTime.class);
            case TIMESTAMP -> rs.getObject(3, LocalDateTime.class);
        };
        return overdue(rs.getString(1), WorkflowDormancySweep.resolve(g, rs.getString(2)), due, kind, zone, context);
    }

    /** The deadline a due value sets, and the date as the decision writes it — from a typed read or a view row's. */
    private static Overdue overdue(String key, String state, Object due, DueColumn kind, ZoneId zone,
                                   Map<String, Object> context) {
        return switch (kind) {
            case DATE -> {
                LocalDate d = due instanceof Date sql ? sql.toLocalDate() : (LocalDate) due;
                yield new Overdue(key, state, endOfDay(d, zone), d.toString(), context);
            }
            case TIMESTAMPTZ -> {
                Instant at = due instanceof Timestamp ts ? ts.toInstant() : ((OffsetDateTime) due).toInstant();
                yield new Overdue(key, state, at, at.toString(), context);
            }
            case TIMESTAMP -> {
                LocalDateTime t = due instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) due;
                yield new Overdue(key, state, t.atZone(zone).toInstant(), t.toString(), context);
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
        p.put("message", DueMessage.render(g, o.key(), state, o.due(), o.context()));
        p.put("workflow", g.name());
        p.put("doc_key", o.key());
        p.put("state", state);
        p.put("due", o.due());
        p.put("due_at", o.dueAt().toString());
        if (!o.context().isEmpty()) p.put("fields", DecisionContext.fields(o.context()));
        String payload = json.writeValueAsString(p);
        Jdbc.update(c, "WorkflowDueSweep.emit", EMIT_SQL, ps -> {
            ps.setLong(1, tenant);
            ps.setString(2, "decision.overdue." + g.name());
            ps.setString(3, "workflow:" + g.name());
            ps.setString(4, g.table());
            ps.setString(5, OutboxKey.bounded(o.key()));
            ps.setString(6, payload);
        });
    }

    /** The column's type as the catalog names it, null when it is not there — as the readonly role, which reads it. */
    private String columnType(String table, String column) {
        try (Connection c = readonlyDs.getConnection()) {
            return WorkflowDataTruth.columnType(c, table, column);
        } catch (SQLException e) {
            throw new RuntimeException(Jdbc.failureText("WorkflowDueSweep.columnType", e), e);
        }
    }

    private List<Long> tenants() {
        return Jdbc.query(adminDs, "WorkflowDueSweep.tenants", TENANTS_SQL, ps -> {}, rs -> rs.getLong(1));
    }
}
