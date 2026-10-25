package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.lumnik.common.sql.Jdbc;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static io.lumnik.hub.workflow.WorkflowManifestParser.norm;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * The dormancy sweep — the memory of the breathing, read on a clock. For each enabled
 * workflow declaring dormancy: latest version per business key from the dynamic table,
 * reconciled against the observation ledger (V33); non-terminal + observed unchanged past
 * the declared threshold + not yet alerted → one decision.dormant.<workflow> outbox event.
 *
 * <p>Cold start is honest: the first sweep records "since now" for everything — nothing can
 * be dormant before the threshold elapses from OUR first observation. All claims are
 * observation-relative; the hub cannot know what happened before its first breath.
 *
 * <p>Admin DS only (transactions=disabled — plain JDBC, no JTA anywhere: the #200 lesson).
 * Per-workflow fail-closed: one broken declaration logs and never blocks the others.
 */
@ApplicationScoped
public class WorkflowDormancySweep {

    private static final Logger LOG = Logger.getLogger(WorkflowDormancySweep.class);
    /** core.event_outbox.entity_key / dedup_key are VARCHAR(200) (V24). */
    private static final int OUTBOX_KEY_MAX = 200;

    private static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, dedup_key, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW()) "
        + "ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING";

    /** Rows the sweep holds at a time: the hub's heap does not depend on how many documents a workflow has. */
    private static final int PAGE = 1_000;

    private static final String TENANTS_SQL =
        "SELECT DISTINCT tenant_id FROM core.workflow_manifest WHERE enabled";

    /**
     * The SQLSTATEs that mean <em>the declaration outran the data</em>: the trigger table is gone
     * (42P01), or the status/key column is (42703 — a dropped column, or a {@code spec.on.key}
     * typo, which applies with a clean 200 and only ever shows up here).
     *
     * <p>Two codes, named one by one rather than the whole 42 class. A syntax error is also class
     * 42, and one of those would be OUR defect in the statement this class builds — the last thing
     * that should go quiet.
     */
    private static final Map<String, String> DECLARATION_OUTRAN_DATA = Map.of(
        "42P01", "the table no longer exists",
        "42703", "a declared column no longer exists");

    /**
     * (tenant, workflow) → the reason already reported, so a standing condition is said ONCE.
     *
     * <p>In memory, not in a table: a restart re-saying it once is the right amount of noise for a
     * condition that is still true, and the alternative is a migration and a row to keep in step
     * with a manifest that can be re-applied at any moment.
     */
    private final Map<String, String> stoodDown = new ConcurrentHashMap<>();

    @Inject WorkflowRegistry registry;
    @Inject WorkflowObservationStore observations;
    @Inject ObjectMapper json;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Scheduled(every = "${lumnik.workflow.sweep.every:15m}", concurrentExecution = SKIP)
    void tick() {
        sweepNow();
    }

    public void sweepNow() {
        for (long tenant : tenants()) {
            for (WorkflowGraph g : registry.allParsable(tenant)) {
                if (g.dormancy() == null || g.key() == null) continue;
                try {
                    sweepOne(tenant, g);
                    noteSweepSucceeded(tenant, g);
                } catch (RuntimeException e) {
                    // fail-closed per workflow: never let one broken declaration stop the rest
                    noteSweepFailed(tenant, g, e);
                }
            }
        }
    }

    /**
     * What a failed tick says. A declaration the schema no longer matches gets ONE line and then
     * silence; everything else keeps the error it always had, on every tick.
     *
     * <p>The entities campaign of 2026-09-12 deleted a connector under a live kind:Workflow and the
     * sweep logged the same ERROR every fifteen minutes from then on, with no delete for a workflow
     * to make it stop (#492). A missing table is not an incident that repeats — it is a state, and a
     * state is worth saying once.
     *
     * <p>A statement timeout, a revoked grant, an exhausted pool stay loud on purpose: those are
     * transient or operational, an operator is meant to see them recur, and standing down from them
     * would make this fix the reason the next outage passed unnoticed.
     *
     * <p>Package-private for {@code WorkflowDormancySweepQuietTest}.
     */
    void noteSweepFailed(long tenant, WorkflowGraph g, RuntimeException e) {
        String sqlState = Jdbc.sqlStateOf(e);
        String what = sqlState == null ? null : DECLARATION_OUTRAN_DATA.get(sqlState);
        if (what == null) {
            LOG.errorf(e, "dormancy sweep failed for tenant %d workflow %s — skipped",
                tenant, g.name());
            return;
        }
        // Comparing against the PREVIOUS value is what makes a condition that CHANGES (the table
        // came back, but spec.on.key now names a column that is not there) news again instead of
        // being swallowed as "already said" — a_condition_that_changes_is_news_again pins it, and
        // two_tenants_of_the_same_workflow_name_are_two_conditions pins the other half.
        //
        // What is in the key and what is in the value is stated once, on the field. Two rounds of
        // review went on this line because it re-described the map from memory and drifted both
        // times ("the reason is part of the key", then "the key is the workflow"); the description
        // that was right the whole time is the one that sits next to the declaration.
        String alreadySaid = stoodDown.put(key(tenant, g), sqlState);
        if (sqlState.equals(alreadySaid)) return;
        LOG.warnf("dormancy sweep standing down for tenant %d workflow %s: %s (%s.%s, SQLSTATE %s)."
            + " Nothing further will be logged for it until the sweep succeeds again — re-ingest the"
            + " source, or re-apply the workflow against a table that exists.",
            tenant, g.name(), what, g.table(), g.column(), sqlState);
    }

    /**
     * A tick that worked. Silent, unless it is the one that ends a stand-down — re-ingesting the
     * source recreates the table and the sweep resumes by itself, which is worth exactly one line.
     * Clearing the mark also re-arms the warning, so a second disappearance is said again instead
     * of inheriting the first one's silence.
     */
    void noteSweepSucceeded(long tenant, WorkflowGraph g) {
        if (stoodDown.remove(key(tenant, g)) != null) {
            LOG.infof("dormancy sweep resumed for tenant %d workflow %s — %s.%s is readable again",
                tenant, g.name(), g.table(), g.column());
        }
    }

    /** Tenant AND workflow: one métier's table vanishing is not another tenant's news. */
    private static String key(long tenant, WorkflowGraph g) {
        return tenant + ":" + g.name();
    }

    private void sweepOne(long tenant, WorkflowGraph g) {
        Instant now = Instant.now();
        Map<String, String> terminalByNorm = new LinkedHashMap<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            if (s.terminal()) terminalByNorm.put(norm(s.code()), s.code());
        }

        // A name of its own per sweep: a cursor that failed to close can then never collide with
        // the next DECLARE on the same pooled connection.
        String cursor = "lumnik_dormancy_" + Long.toHexString(System.nanoTime());
        try (Connection c = adminDs.getConnection()) {
            declareStream(c, cursor, tenant, g);
            try (Statement st = c.createStatement()) {
                int rows;
                do {
                    rows = 0;
                    try (ResultSet rs = st.executeQuery("FETCH FORWARD " + PAGE + " FROM " + cursor)) {
                        while (rs.next()) {
                            rows++;
                            reconcileDocument(tenant, g, terminalByNorm, rs, now);
                        }
                    }
                } while (rows == PAGE);
            } finally {
                closeStream(c, cursor);
            }
        } catch (SQLException e) {
            throw new RuntimeException("WorkflowDormancySweep.sweepOne: " + e.getMessage(), e);
        }
    }

    /** One row of the stream: the document, its latest raw state, and the ledger's entry for it (or none). */
    private void reconcileDocument(long tenant, WorkflowGraph g, Map<String, String> terminalByNorm,
                                   ResultSet rs, Instant now) throws SQLException {
        String key = rs.getString(1);
        String raw = rs.getString(2);
        // Per-DOCUMENT isolation, the project's per-row DLQ posture: a single poison key escaping to
        // the per-workflow net would abandon every document after it — the same ones, on every
        // tick, forever. Reading its ledger entry is part of its work.
        try {
            // doc_key is NULL when the LEFT JOIN found no ledger entry: a document seen for the first time
            WorkflowObservationStore.Observation obs = rs.getString(3) == null ? null
                : WorkflowObservationStore.Observation.fromRow(rs, 3);
            reconcileOne(tenant, g, terminalByNorm, obs, key, raw, now);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "dormancy: document skipped (tenant %d, workflow %s, key %s)",
                tenant, g.name(), key);
        }
    }

    private void reconcileOne(long tenant, WorkflowGraph g, Map<String, String> terminalByNorm,
                              WorkflowObservationStore.Observation obs,
                              String key, String raw, Instant now) {
        String state = resolve(g, raw);
        if (obs == null) {
            observations.insertNew(tenant, g.name(), key, state, now);
            return;
        }
        // Compare normalized, not raw: an undeclared value (resolve() left it raw) can
        // reappear with different casing/whitespace on a later ingest ("EN LITIGE" vs
        // "en litige ") — that is dirty data, not movement, and must not reset `since`
        // or clear the alert flag. The stored value itself stays whatever was observed.
        // And compare under the CURRENT declaration (#1367): a raw value the integrator has
        // since declared as an alias is a new meaning, not a movement — relabel, keep the clock.
        String held = resolve(g, obs.state());
        if (!norm(held).equals(norm(state))) {
            observations.recordTransition(tenant, g.name(), key, held, state, now);
            return;
        }
        if (!held.equals(obs.state())) {
            observations.relabel(tenant, g.name(), key, held);
            obs = obs.withState(held);
        }
        boolean terminal = terminalByNorm.containsKey(norm(state));
        boolean overdue = now.isAfter(obs.since().plus(g.dormancy().duration()));
        if (!terminal && overdue && obs.alertedAt() == null) {
            emit(tenant, g, obs);
            observations.markAlerted(tenant, g.name(), key, now);
        }
    }

    /** The raw column value resolved to its declared code via trim+lowercase aliases;
     *  kept raw when undeclared — an undeclared value cannot be terminal, so a document
     *  sleeping in an unknown state is detectable. That is the sickest dossier there is. */
    static String resolve(WorkflowGraph g, String raw) {
        String n = norm(raw);
        for (WorkflowGraph.StateDto s : g.states()) {
            if (norm(s.code()).equals(n)) return s.code();
            for (String a : s.aliases()) if (norm(a).equals(n)) return s.code();
        }
        return raw;
    }

    /**
     * Opens the sweep's stream: every document of the workflow with its latest raw state, joined to
     * its ledger entry, as a cursor the DATABASE holds. The hub reads it {@value #PAGE} rows at a
     * time, so its heap does not grow with the number of documents (#905).
     *
     * <p>{@code WITH HOLD} is the point, not decoration; {@code setFetchSize}, which the other
     * streamers use, is not enough here. A plain cursor lives inside its transaction, and that
     * transaction would keep a lock on the source table for as long as the documents take to
     * reconcile — and ingestion issues
     * {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS}, which waits for ACCESS EXCLUSIVE and stalls
     * every later reader behind itself. A held cursor is materialised when its statement commits and
     * the lock goes with it: the table is locked for the database's own read of it (the sort and the
     * copy of the result, which grow with the table), and no longer for the reconciliation, which
     * writes one document at a time and is the slower half.
     */
    private void declareStream(Connection c, String cursor, long tenant, WorkflowGraph g) throws SQLException {
        // JDBC cannot bind an identifier, so each one is interpolated QUOTED — the escaping is the
        // guard, and the name is whatever the source called it ("Statut Commande", "N° Commande").
        // Bare, those are not a miss but a SYNTAX ERROR, and the per-workflow net above would
        // swallow it as a logged skip: a declared dormancy that never fires, on every tick, forever.
        String key = quoteIdent(g.key());
        String column = quoteIdent(g.column());
        String sql = "DECLARE " + cursor + " CURSOR WITH HOLD FOR "
            + "SELECT s.k, s.v, " + WorkflowObservationStore.Observation.columns("o.") + " FROM ("
            + "SELECT DISTINCT ON (" + key + ") " + key + "::text AS k, "
            + column + "::text AS v FROM " + quoteTable(g.table())
            + " WHERE tenant_id = ? AND " + key + " IS NOT NULL AND btrim(" + key + "::text) <> '' AND "
            + column + " IS NOT NULL AND btrim(" + column + "::text) <> '' "
            // `id DESC` is not decoration: `_ingested_at` defaults to now(), the TRANSACTION
            // timestamp, so every row of one ingest chunk shares it EXACTLY — and two rows for the
            // same business key in one chunk is routine in legacy exports. Without a tiebreaker
            // DISTINCT ON picks arbitrarily, the sweep flip-flops between the two versions, and the
            // append-only journal fills with transitions that never happened. Every dynamic table
            // has `id BIGSERIAL PRIMARY KEY` (DynamicTableWriter), so this is a total order.
            + "ORDER BY " + key + ", _ingested_at DESC, id DESC) s "
            + "LEFT JOIN core.workflow_observation o "
            + "ON o.tenant_id = ? AND o.workflow_name = ? AND o.doc_key = s.k";
        // The DECLARE must commit by itself: inside a transaction left open it would keep the table
        // locked, whatever WITH HOLD says. Said here rather than assumed of the pool.
        c.setAutoCommit(true);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant);
            ps.setLong(2, tenant);
            ps.setString(3, g.name());
            ps.execute();
        }
    }

    /** A held cursor outlives its transaction, so it is closed here, not left to the pool's next borrower. */
    private void closeStream(Connection c, String cursor) {
        try (Statement st = c.createStatement()) {
            st.execute("CLOSE " + cursor);
        } catch (SQLException e) {
            LOG.warnf(e, "dormancy: could not close cursor %s — it stays on that session until the session ends", cursor);
        }
    }

    private void emit(long tenant, WorkflowGraph g, WorkflowObservationStore.Observation obs) {
        String message = DormancyMessage.render(g, obs);
        String payload;
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("message", message);
            p.put("workflow", g.name());
            p.put("doc_key", obs.docKey());
            p.put("state", obs.state());
            p.put("since", obs.since().toString());
            p.put("first_observed_at", obs.firstObservedAt().toString());
            p.put("threshold", g.dormancy().after());
            payload = json.writeValueAsString(p);
        } catch (Exception e) {
            throw new RuntimeException("WorkflowDormancySweep.emit: payload build failed", e);
        }
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(EMIT_SQL)) {
            ps.setLong(1, tenant);
            ps.setString(2, "decision.dormant." + g.name());
            ps.setString(3, "workflow:" + g.name());
            ps.setString(4, g.table());
            ps.setString(5, bounded(obs.docKey()));
            ps.setString(6, payload);
            ps.setString(7, bounded(
                "dormant:" + g.name() + ":" + obs.docKey() + ":" + obs.since().toEpochMilli()));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("WorkflowDormancySweep.emit: " + e.getMessage(), e);
        }
    }

    /** {@code core.event_outbox.entity_key} and {@code dedup_key} are VARCHAR(200) (V24), but a
     *  doc key is raw text from an arbitrary customer column and has no bound at all. Readable
     *  while it fits; beyond that a stable digest of the whole value — still deterministic, so
     *  ON CONFLICT still dedupes, and the row is emitted instead of a 22001 killing the alert.
     *  The payload keeps the real key verbatim (jsonb is unbounded): the operator must be able to
     *  see which document this is. Measured in UTF-16 units, i.e. conservatively. */
    static String bounded(String s) {
        if (s == null || s.length() <= OUTBOX_KEY_MAX) return s;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
        }
    }

    private List<Long> tenants() {
        List<Long> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(TENANTS_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (Exception e) {
            throw new RuntimeException("WorkflowDormancySweep.tenants: " + e.getMessage(), e);
        }
        return out;
    }
}
