package io.lumnik.hub.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.event.DeadLetterReplayer;
import io.lumnik.hub.event.EventDeadLetters;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.OutboxKey;
import io.lumnik.hub.event.RowEventGate;
import io.lumnik.hub.event.consumer.BatchProcessor;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.view.ViewFilter;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Outbox consumer "process" — the kind:Process notify rung (É2 §9 rung 2). Sweeps
 * data.row.changed, evaluates each enabled process's when-conditions against the row content
 * (fetched by the table's hash column: _row_hash, or row_hash in a hybrid table), and on match
 * emits the declared decision.* event into the same outbox.
 * dedup_key = process:&lt;name&gt;:&lt;triggering event id&gt; makes a cursor rewind replay-safe (the V24
 * partial unique index). Per-process fail-closed: a broken process logs and never blocks the
 * cursor (the É2 no-head-of-line rule).
 */
@ApplicationScoped
public class ProcessConsumer implements DeadLetterReplayer {

    static final String CONSUMER_NAME = "process";
    /** A dead letter of one Process: {@code process:<name>}. */
    private static final String TARGET_PREFIX = "process:";
    private static final Logger LOG = Logger.getLogger(ProcessConsumer.class);

    private static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, dedup_key, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW()) "
        + "ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING";

    @Inject OutboxSweeper sweeper;
    @Inject ProcessManifestStore store;
    @Inject ObjectMapper json;
    @Inject DynamicTableRegistry tables;
    @Inject EntityProcessTargets entityTargets;
    @Inject FusedRowReader fused;
    @Inject LookupFanOut fanOut;
    @Inject EventDeadLetters deadLetters;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    void tick() {
        TenantContext.runAsSystem(() -> sweeper.drain(CONSUMER_NAME, 100, this::batchProcessor));
    }

    /** One tenant's table — what a batch looks its processes up by. */
    private record TableOf(long tenantId, String table) {}

    /** A process with its {@code when} parsed: the parse is done when the process is loaded, not once per row. */
    record Rule(ProcessManifestStore.StoredProcess process, List<ViewFilter> conds) {
        static Rule of(ProcessManifestStore.StoredProcess p) {
            List<ViewFilter> conds = new ArrayList<>();
            for (String w : p.when()) conds.add(ViewFilter.parseProcessCondition(w));
            return new Rule(p, conds);
        }
    }

    /**
     * The processes on one Entity that fuses the changed table (#1833): the Entity's view, the match key of the
     * changed source row as that source spells it, and the rules to evaluate on the fused row it belongs to.
     */
    private record EntityListening(FusedRowReader.FusedView view, List<String> keyExprs, List<Rule> rules) {}

    /**
     * The processes on one Entity that JOINS the changed table (#1834): the reaches the changed lookup row has on the
     * Entity (one per join and source that read it), and the rules whose work is to be queued.
     */
    record LookupListening(List<LookupReach> reaches, List<Rule> rules) {}

    /** What listens on a table: the processes on it, those on an Entity fusing or joining it, and its hash column. */
    private record Listening(List<Rule> rules, List<EntityListening> entities, List<LookupListening> lookups,
                             String hashColumn) {
        boolean none() { return rules.isEmpty() && entities.isEmpty() && lookups.isEmpty(); }

        /** What listens, narrowed to the process {@code name} (a replay redoes one Process). */
        Listening narrowedTo(String name) {
            return new Listening(named(rules, name),
                entities.stream().map(e -> new EntityListening(e.view(), e.keyExprs(), named(e.rules(), name)))
                    .filter(e -> !e.rules().isEmpty()).toList(),
                lookups.stream().map(l -> new LookupListening(l.reaches(), named(l.rules(), name)))
                    .filter(l -> !l.rules().isEmpty()).toList(),
                hashColumn);
        }

        private static List<Rule> named(List<Rule> rules, String name) {
            return rules.stream().filter(r -> r.process().name().equals(name)).toList();
        }
    }

    /**
     * The processor of one outbox batch. The processes on a tenant's table are read (and their
     * YAML parsed) at its first event in the batch and reused for the rest of it, never for the
     * next one: a process applied, disabled or deleted is seen from the next batch on, so at most
     * the rest of the current batch is late. The table's hash column is asked of the registry the
     * same way, once per batch, and only for a table some process listens on.
     */
    public BatchProcessor batchProcessor() {
        Map<TableOf, Listening> listeningOn = new HashMap<>();
        return row -> process(row, listeningOn);
    }

    private Listening listening(TableOf t) {
        List<Rule> rules = rulesOf(store.findEnabledFor(t.tenantId(), t.table()));
        List<EntityListening> entities = entitiesOf(t);
        List<LookupListening> lookups = lookupsOf(t);
        Listening listening = new Listening(rules, entities, lookups, null);
        return listening.none() ? listening : new Listening(rules, entities, lookups, tables.rowHashColumn(t.tenantId(), t.table()));
    }

    /**
     * The processes on the Entities that fuse {@code t}. Whatever goes wrong with an Entity (a stored definition that cannot
     * be read, a source dropped) is said and leaves THAT Entity out: the sweeper moves its cursor past an event whatever
     * this throws, so a failure here would lose the event for every process of the table, the ordinary ones too (#1844).
     */
    private List<EntityListening> entitiesOf(TableOf t) {
        Map<String, List<EntityProcessTargets.Target>> byEntity = new LinkedHashMap<>();
        try {
            for (EntityProcessTargets.Target target : entityTargets.observing(t.tenantId(), t.table()))
                byEntity.computeIfAbsent(target.entity().name(), k -> new ArrayList<>()).add(target);
        } catch (RuntimeException e) {
            LOG.errorf("processes on an Entity fusing %s: the Entity targets cannot be resolved (%s) — none is evaluated for this batch", t.table(), e.getClass().getSimpleName());
            LOG.debug("Entity read failure", e);
            return List.of();
        }
        List<EntityListening> entities = new ArrayList<>();
        for (List<EntityProcessTargets.Target> targets : byEntity.values()) {
            try {
                List<Rule> entityRules = rulesOf(targets.stream().map(EntityProcessTargets.Target::process).toList());
                if (entityRules.isEmpty()) continue;
                EntityManifest m = targets.get(0).entity();
                EntityViewCompiler.Sources catalog = fused.catalog(m);
                String bare = m.sources().stream().filter(src -> t.table().equals(src.fqn())).findFirst().orElseThrow().table();
                entities.add(new EntityListening(
                    new FusedRowReader.FusedView(targets.get(0).process().onTable(), m.match(),
                        EntityViewCompiler.matchColumnTypes(m, catalog)),
                    EntityViewCompiler.matchKeyExpressions(m, catalog, bare), entityRules));
            } catch (RuntimeException e) {
                LOG.errorf("processes on Entity '%s' fusing %s cannot be set up (%s) — it is not evaluated for this batch",
                    targets.get(0).entity().name(), t.table(), e.getClass().getSimpleName());
                LOG.debug("Entity set-up failure", e);
            }
        }
        return entities;
    }

    /** The processes whose Entity joins {@code t}: per Entity, its reaches through that table and the rules to queue. */
    private List<LookupListening> lookupsOf(TableOf t) {
        Map<String, List<EntityProcessTargets.Target>> byEntity = new LinkedHashMap<>();
        try {
            for (EntityProcessTargets.Target target : entityTargets.joining(t.tenantId(), t.table()))
                byEntity.computeIfAbsent(target.entity().name(), k -> new ArrayList<>()).add(target);
        } catch (RuntimeException e) {
            LOG.errorf("processes on an Entity joining %s: the Entity targets cannot be resolved (%s) — none is queued for this batch", t.table(), e.getClass().getSimpleName());
            LOG.debug("Entity read failure", e);
            return List.of();
        }
        List<LookupListening> out = new ArrayList<>();
        for (List<EntityProcessTargets.Target> targets : byEntity.values()) {
            try {
                List<Rule> rules = rulesOf(targets.stream().map(EntityProcessTargets.Target::process).toList());
                EntityManifest m = targets.get(0).entity();
                List<LookupReach> reaches = LookupReach.of(m, fused.catalog(m)).stream()
                    .filter(r -> t.table().equals(r.lookupTable())).toList();
                if (!rules.isEmpty() && !reaches.isEmpty()) out.add(new LookupListening(reaches, rules));
            } catch (RuntimeException e) {
                LOG.errorf("processes on Entity '%s' joining %s cannot be set up (%s) — nothing is queued for this batch",
                    targets.get(0).entity().name(), t.table(), e.getClass().getSimpleName());
                LOG.debug("Entity set-up failure", e);
            }
        }
        return out;
    }

    /** Fail-closed per process: one whose {@code when} cannot be read is left out, never the others with it. */
    private static List<Rule> rulesOf(List<ProcessManifestStore.StoredProcess> procs) {
        List<Rule> out = new ArrayList<>();
        for (ProcessManifestStore.StoredProcess p : procs) {
            try {
                out.add(Rule.of(p));
            } catch (RuntimeException e) {
                LOG.errorf(e, "process '%s' has a when: that cannot be read — skipped", p.name());
            }
        }
        return out;
    }

    private void process(EventOutboxRow row, Map<TableOf, Listening> listeningOn) {
        if (!RowEventGate.DATA_ROW_CHANGED.equals(row.getEventType())) return;
        evaluate(row, listeningOn.computeIfAbsent(new TableOf(row.getTenantId(), row.getEntity()), this::listening), toOutbox);
    }

    /** @param sink {@link #toOutbox} swallows a process's failure into a dead letter; any other sink lets it through */
    private void evaluate(EventOutboxRow row, Listening on, DecisionSink sink) {
        if (on.none()) return;

        Long rowId = SourceRows.HYBRID_HASH.equals(on.hashColumn()) ? rowIdOf(row) : null;
        if (SourceRows.HYBRID_HASH.equals(on.hashColumn()) && rowId == null) {
            LOG.debugf("process consumer: event %d on %s carries no row_id — skipped", row.getId(), row.getEntity());
            return;
        }
        if (!on.rules().isEmpty()) onTable(row, on, rowId, sink);
        for (EntityListening e : on.entities()) onEntity(row, on, rowId, e, sink);
        for (LookupListening l : on.lookups()) onLookup(row, on.hashColumn(), rowId, l, sink);
    }

    /** What a dead letter names when one Process failed on the event: replayed, only that Process is evaluated. */
    private static String target(Rule r) {
        return TARGET_PREFIX + r.process().name();
    }

    /** A process that failed on an event: said, and kept as a dead letter of its own. */
    private void failedOn(Rule r, long eventId, RuntimeException e) {
        LOG.errorf(e, "process '%s' failed on event %d — kept as a dead letter", r.process().name(), eventId);
        deadLetters.record(CONSUMER_NAME, eventId, target(r), e);
    }

    @Override
    public String consumer() {
        return CONSUMER_NAME;
    }

    /**
     * Redoes the event for the Process the letter names, or for all of them when it names none (decisions dedup). A failure
     * is the replay's: it comes out to the caller, who answers it and leaves the letter open, and nothing left to listen
     * is one too — a replay that did nothing must not close the letter.
     */
    @Override
    public void replay(EventOutboxRow event, String targetKey) {
        if (!RowEventGate.DATA_ROW_CHANGED.equals(event.getEventType())) return;
        Listening on = listening(new TableOf(event.getTenantId(), event.getEntity()));
        String name = targetKey.startsWith(TARGET_PREFIX) ? targetKey.substring(TARGET_PREFIX.length()) : null;
        if (name != null) on = on.narrowedTo(name);
        if (on.none()) throw new IllegalStateException(name == null ? "no process listens on the event any more"
            : "process '" + name + "' no longer listens on the event");
        evaluate(event, on, replayOutbox);
    }

    private void onTable(EventOutboxRow row, Listening on, Long rowId, DecisionSink sink) {
        Map<String, Object> content =
            fetchRow(row.getEntity(), row.getTenantId(), on.hashColumn(), rowId, row.getEntityKey());
        if (content == null) {
            LOG.debugf("process consumer: row %s no longer in %s — skipped", row.getEntityKey(), row.getEntity());
            return;
        }
        for (Rule r : on.rules()) {
            decide(sink, row.getTenantId(), row.getId(), r, content, row.getEntity(), row.getEntityKey(),
                "process:" + r.process().name() + ":" + row.getId());
        }
    }

    /**
     * A source row changed: evaluate the fused row it belongs to. The row is read once for every process on the
     * Entity, and one decision comes out per (process, event, canonical key) — a physical change that still satisfies the
     * rule decides again, as a Process on a table does.
     */
    private void onEntity(EventOutboxRow row, Listening on, Long rowId, EntityListening e, DecisionSink sink) {
        String view = e.view().view();
        try {
            Optional<FusedRowReader.FusedKey> key = fused.key(row, e.keyExprs(), on.hashColumn(), rowId);
            if (key.isEmpty()) {
                LOG.debugf("process consumer: row %s of %s belongs to no row of %s — skipped", row.getEntityKey(), row.getEntity(), view);
                return;
            }
            Map<String, Object> content = fused.read(e.view(), key.get(), row.getTenantId());
            if (content == null) {
                LOG.debugf("process consumer: %s has no row for the key of %s — skipped", view, row.getEntityKey());
                return;
            }
            String canonical = key.get().canonical();
            for (Rule r : e.rules()) {
                decideOnFused(sink, row.getTenantId(), row.getId(), r, content, view, canonical);
            }
        } catch (RuntimeException ex) {
            if (sink != toOutbox) throw ex;
            for (Rule r : e.rules()) failedOn(r, row.getId(), ex);
        }
    }

    /**
     * A lookup row changed: queue the fan-out over the fused rows it reaches (#1834), one work row per (process, event,
     * reach), and return — the cursor advances at once. The rows are evaluated by {@link LookupFanOut}, a page at a time:
     * a fan-out of 100,000 rows inside the event would hold this consumer's cursor, and its connection, for all of it.
     * A lookup row without a key reaches nothing; one that is gone is out of contract (the view fuses what exists).
     */
    void onLookup(EventOutboxRow row, String hashColumn, Long rowId, LookupListening l, DecisionSink sink) {
        // The sweeper moves its cursor past an event whatever this throws, so nothing here may count on a retry:
        // LookupFanOut.enqueue retries by itself, and a process it gives up on leaves a letter of its own while the
        // others are still queued. During a replay the failure goes out instead.
        for (LookupReach reach : l.reaches()) {
            Optional<FusedRowReader.FusedKey> key;
            try {
                key = fused.key(row, List.of(reach.toKey()), hashColumn, rowId);
            } catch (RuntimeException e) {
                if (sink != toOutbox) throw e;
                for (Rule r : l.rules()) failedOn(r, row.getId(), e);
                continue;
            }
            if (key.isEmpty()) {
                LOG.debugf("process consumer: lookup row %s of %s has no key — it reaches nothing", row.getEntityKey(), row.getEntity());
                continue;
            }
            for (Rule r : l.rules()) {
                try {
                    fanOut.enqueue(row.getTenantId(), r.process().name(), row.getId(), reach.target(), key.get().values().get(0));
                } catch (RuntimeException e) {
                    if (sink != toOutbox) throw e;
                    failedOn(r, row.getId(), e);
                }
            }
        }
    }

    /** Where a decision is written: the outbox on its own statement, or on a connection whose transaction the caller owns. */
    @FunctionalInterface
    interface DecisionSink {
        void write(String label, Jdbc.Binder binder);
    }

    final DecisionSink toOutbox = (label, binder) -> Jdbc.update(adminDs, label, EMIT_SQL, binder);
    /** The same write, a distinct sink: {@link #decide} lets a failure through on it, as on a page's connection. */
    final DecisionSink replayOutbox = (label, binder) -> Jdbc.update(adminDs, label, EMIT_SQL, binder);

    /** A sink on {@code c}: the decisions of a page are written in the transaction that advances its cursor. */
    static DecisionSink onConnection(Connection c) {
        return (label, binder) -> Jdbc.update(c, label, EMIT_SQL, binder);
    }

    /**
     * One rule against a fused row: a match emits one decision per (process, source event, canonical key) — a physical
     * change that still satisfies the rule decides again, as a Process on a table does, and a replay decides nothing twice.
     */
    void decideOnFused(DecisionSink sink, long tenantId, long eventId, Rule r, Map<String, Object> content, String view,
                       String canonical) {
        decide(sink, tenantId, eventId, r, content, view, canonical, entityDedupKey(r.process().name(), eventId, canonical));
    }

    /**
     * One rule against one row's content: a match emits the decision. Fail-closed per process — a broken declaration is
     * logged and skipped, never allowed to block the cursor.
     *
     * @param fallbackKey the decision's key when the process declares none, or its column is blank on the row
     */
    private void decide(DecisionSink sink, long tenantId, long eventId, Rule r, Map<String, Object> content, String entity,
                        String fallbackKey, String dedupKey) {
        try {
            if (ProcessRuleEvaluator.matches(content, r.conds())) {
                emit(sink, tenantId, r.process(), content, entity, decisionKey(r.process().emitKey(), content, fallbackKey), dedupKey);
            }
        } catch (RuntimeException e) {
            // a statement that failed on the caller's own transaction aborted it: the page must fail with its real cause
            if (sink != toOutbox) throw e;
            failedOn(r, eventId, e);
        }
    }

    /** One decision per process, source event and fused row; bounded as every dedup key is (VARCHAR(200)). */
    static String entityDedupKey(String process, long eventId, String canonicalKey) {
        return OutboxKey.bounded("process:" + process + ":" + eventId + ":" + canonicalKey);
    }

    /** The event's {@code row_id}, a JSON string; {@code null} when it has none, or not a number. */
    private static Long rowIdOf(EventOutboxRow row) {
        if (!(row.getPayload().get("row_id") instanceof String id)) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    /**
     * The read of one event's row. A hybrid table has no index on its hash: its row is found by primary key, and the
     * hash checks that the event names the row's current version. A dynamic table is read by its hash. Both filter
     * the tenant.
     *
     * @param hashColumn one of the registry's two fixed names, never a caller's text: safe to write into the SQL
     */
    static String fetchSql(String table, String hashColumn) {
        return "SELECT * FROM " + quoteTable(table) + SourceRows.where(hashColumn);
    }

    /** @param rowId the hybrid row's id, {@code null} for a dynamic table */
    private Map<String, Object> fetchRow(String table, Long tenantId, String hashColumn, Long rowId, String rowHash) {
        return Jdbc.queryOne(adminDs, "ProcessConsumer.fetchRow", fetchSql(table, hashColumn),
                SourceRows.binder(rowId, tenantId, rowHash), SourceRows::toMap).orElse(null);
    }

    /**
     * The decision's key: the declared column's value, rendered as a {@code {col}} is, else the row hash — also when
     * that value is null or blank, so a decision always has a key (#1823).
     */
    static String decisionKey(String column, Map<String, Object> row, String rowHash) {
        if (column == null) return rowHash;
        String value = MessageTemplate.text(row.get(column));
        return Text.blank(value) ? rowHash : value;
    }

    /**
     * @param entity the table, or the Entity's view, the decision is about
     * @param key the decision's key, whole (the outbox column keeps a bounded form of it)
     * @param dedupKey one value per decision: a replay of the same event cannot emit it twice
     */
    private void emit(DecisionSink sink, long tenantId, ProcessManifestStore.StoredProcess p,
                      Map<String, Object> content, String entity, String key, String dedupKey) {
        String payload;
        try {
            payload = buildPayload(json, p.emitMessage(), p.emitFields(), p.name(), entity, key, content);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ProcessConsumer.emit: payload build failed: " + e.getMessage(), e);
        }
        sink.write("ProcessConsumer.emit", ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, p.emitType());
            ps.setString(3, "process:" + p.name());
            ps.setString(4, entity);
            ps.setString(5, OutboxKey.bounded(key));   // the payload keeps the key whole
            ps.setString(6, payload);
            ps.setString(7, dedupKey);
        });
    }

    /** The decision event payload as valid JSON — Jackson-escaped so any message text is safe.
     *  {col} placeholders render from the row; the referenced columns ride along as `fields`
     *  (structured data for webhook consumers — n8n reads data, not text), and so do the declared
     *  {@code extra} ones, which the sentence a human reads need not name (#1365). */
    static String buildPayload(ObjectMapper json, String template, List<String> extra, String process,
                               String entity, String entityKey, Map<String, Object> row)
            throws JsonProcessingException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", MessageTemplate.render(template, row));
        m.put("process", process);
        m.put("entity", entity);
        m.put("entity_key", entityKey);
        Set<String> refs = new LinkedHashSet<>(MessageTemplate.placeholders(template));
        refs.addAll(extra);
        if (!refs.isEmpty()) m.put("fields", MessageTemplate.fields(refs, row));
        return json.writeValueAsString(m);
    }

}
