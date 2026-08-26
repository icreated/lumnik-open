package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    /** core.event_outbox.entity_key / dedup_key are VARCHAR(200) (V24). */
    private static final int OUTBOX_KEY_MAX = 200;

    private static final String EMIT_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, dedup_key, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW()) "
        + "ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING";

    private static final String TENANTS_SQL =
        "SELECT DISTINCT tenant_id FROM core.workflow_manifest WHERE enabled";

    @Inject WorkflowRegistry registry;
    @Inject WorkflowObservationStore observations;
    @Inject ObjectMapper json;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @Scheduled(every = "${lumnik.workflow.sweep.every:15m}", concurrentExecution = SKIP)
    void tick() {
        sweepNow();
    }

    public void sweepNow() {
        for (long tenant : tenants()) {
            for (WorkflowGraph g : registry.all(tenant)) {
                if (g.dormancy() == null || g.key() == null) continue;
                try {
                    sweepOne(tenant, g);
                } catch (RuntimeException e) {
                    // fail-closed per workflow: never let one broken declaration stop the rest
                    LOG.errorf(e, "dormancy sweep failed for tenant %d workflow %s — skipped",
                        tenant, g.name());
                }
            }
        }
    }

    private void sweepOne(long tenant, WorkflowGraph g) {
        Instant now = Instant.now();
        Map<String, String> current = latestStatePerKey(tenant, g);
        Map<String, WorkflowObservationStore.Observation> known =
            observations.loadAll(tenant, g.name());
        Map<String, String> terminalByNorm = new LinkedHashMap<>();
        for (WorkflowGraph.StateDto s : g.states()) {
            if (s.terminal()) terminalByNorm.put(norm(s.code()), s.code());
        }

        for (Map.Entry<String, String> e : current.entrySet()) {
            // Per-DOCUMENT isolation, the project's per-row DLQ posture: the result order is
            // deterministic, so a single poison key escaping to the per-workflow net below would
            // abandon every document after it — the same ones, on every tick, forever.
            try {
                reconcileOne(tenant, g, terminalByNorm, known, e.getKey(), e.getValue(), now);
            } catch (RuntimeException ex) {
                LOG.warnf(ex, "dormancy: document skipped (tenant %d, workflow %s, key %s)",
                    tenant, g.name(), e.getKey());
            }
        }
    }

    private void reconcileOne(long tenant, WorkflowGraph g, Map<String, String> terminalByNorm,
                              Map<String, WorkflowObservationStore.Observation> known,
                              String key, String raw, Instant now) {
        String state = resolve(g, raw);
        WorkflowObservationStore.Observation obs = known.get(key);
        if (obs == null) {
            observations.insertNew(tenant, g.name(), key, state, now);
            return;
        }
        // Compare normalized, not raw: an undeclared value (resolve() left it raw) can
        // reappear with different casing/whitespace on a later ingest ("EN LITIGE" vs
        // "en litige ") — that is dirty data, not movement, and must not reset `since`
        // or clear the alert flag. The stored value itself stays whatever was observed.
        if (!norm(obs.state()).equals(norm(state))) {
            observations.recordTransition(tenant, g.name(), key, obs.state(), state, now);
            return;
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
    private static String resolve(WorkflowGraph g, String raw) {
        String n = norm(raw);
        for (WorkflowGraph.StateDto s : g.states()) {
            if (norm(s.code()).equals(n)) return s.code();
            for (String a : s.aliases()) if (norm(a).equals(n)) return s.code();
        }
        return raw;
    }

    private Map<String, String> latestStatePerKey(long tenant, WorkflowGraph g) {
        // Identifiers are parse-time grammar-guarded (TABLE_RX/COLUMN_RX); the graph came
        // from the registry, i.e. from a re-parsed manifest — the standing injection invariant.
        String sql = "SELECT DISTINCT ON (" + g.key() + ") " + g.key() + "::text, "
            + g.column() + "::text FROM " + quoteTable(g.table())
            + " WHERE tenant_id = ? AND " + g.key() + " IS NOT NULL AND btrim(" + g.key() + "::text) <> '' AND "
            + g.column() + " IS NOT NULL AND btrim(" + g.column() + "::text) <> '' "
            // `id DESC` is not decoration: `_ingested_at` defaults to now(), the TRANSACTION
            // timestamp, so every row of one ingest chunk shares it EXACTLY — and two rows for the
            // same business key in one chunk is routine in legacy exports. Without a tiebreaker
            // DISTINCT ON picks arbitrarily, the sweep flip-flops between the two versions, and the
            // append-only journal fills with transitions that never happened. Every dynamic table
            // has `id BIGSERIAL PRIMARY KEY` (DynamicTableWriter), so this is a total order.
            + "ORDER BY " + g.key() + ", _ingested_at DESC, id DESC";
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowDormancySweep.latestStatePerKey: " + e.getMessage(), e);
        }
        return out;
    }

    private void emit(long tenant, WorkflowGraph g, WorkflowObservationStore.Observation obs) {
        String message = String.format(
            "%s : observé immobile en « %s » depuis le %s (première observation %s) — seuil déclaré %s dans %s",
            obs.docKey(), obs.state(), DAY.format(obs.since()),
            DAY.format(obs.firstObservedAt()), g.dormancy().after(), g.name());
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
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
        }
    }

    private List<Long> tenants() {
        List<Long> out = new java.util.ArrayList<>();
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
