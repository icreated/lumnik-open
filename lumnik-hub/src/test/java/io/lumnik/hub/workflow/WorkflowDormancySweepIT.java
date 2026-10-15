package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowDormancySweepIT {

    @Inject WorkflowDormancySweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject WorkflowObservationStore observations;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final long TENANT = 7501L;
    private static final String WF = "wf-sweep-it";
    private static final String TABLE = "connector.t_wf_sweep_it";

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-sweep-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_sweep_it, column: statut, key: num }
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
            s.execute("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, num TEXT, statut TEXT,"
                + "  _ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            s.execute("DELETE FROM " + TABLE);
            s.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.workflow_transition WHERE tenant_id = " + TENANT);
            s.execute("DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT);
            // ACTIF sleeps in a declared state; LITIGE sleeps in an UNDECLARED one; FINI is terminal
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES "
                + "(" + TENANT + ", 'CMD-ACTIF', 'en cours'),"
                + "(" + TENANT + ", 'CMD-LITIGE', 'EN LITIGE'),"
                + "(" + TENANT + ", 'CMD-FINI', 'fini')");
        }
        manifests.upsert(TENANT, new WorkflowManifestParser().parse(YAML), YAML, null);
    }

    private void ageAll() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 minutes' "
                + "WHERE tenant_id = " + TENANT);
        }
    }

    private long transitionCount(String docKey) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM core.workflow_transition WHERE tenant_id = ? AND doc_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, docKey);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private List<Map<String, String>> dormantEvents() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT event_type, entity_key, payload::text FROM core.event_outbox "
                    + "WHERE tenant_id = ? AND event_type LIKE 'decision.dormant.%' ORDER BY id")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> out = new ArrayList<>();
                while (rs.next()) out.add(Map.of("type", rs.getString(1),
                    "key", rs.getString(2), "payload", rs.getString(3)));
                return out;
            }
        }
    }

    @Test
    void cold_start_then_dormancy_then_idempotence_then_realert() throws Exception {
        // Sweep 1 — cold start: everything is "since now", nothing can be dormant. Honest.
        sweep.sweepNow();
        assertThat(dormantEvents()).isEmpty();
        assertThat(observations.loadAll(TENANT, WF)).containsKeys("CMD-ACTIF", "CMD-LITIGE", "CMD-FINI");

        // Sweep 2 after aging — the sleeper in a DECLARED state and the sleeper in an
        // UNDECLARED state both alert; the terminal one never does.
        ageAll();
        sweep.sweepNow();
        List<Map<String, String>> events = dormantEvents();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(e -> e.get("key"))
            .containsExactlyInAnyOrder("CMD-ACTIF", "CMD-LITIGE");
        assertThat(events).allSatisfy(e ->
            assertThat(e.get("type")).isEqualTo("decision.dormant.wf-sweep-it"));

        // The message obeys the temporal-honesty vocabulary. Jackson does NOT escape
        // non-ASCII (see the captured payload in docs/demo/wow-customer/README.md:239 —
        // "Nouveau client Claire Martin à Lyon" lands verbatim), and the build is UTF-8
        // (project.build.sourceEncoding), so assert the accented text directly.
        String payload = events.get(0).get("payload");
        assertThat(payload).contains("observé immobile").contains("première observation");
        assertThat(payload).doesNotContain("bloqu");

        // The Java gate itself fired — alerted_at is actually set on both sleepers, not
        // just "the count happens to still be 2" (which the outbox's own ON CONFLICT would
        // also produce even if the alertedAt-null gate were deleted outright).
        Map<String, WorkflowObservationStore.Observation> afterAlert = observations.loadAll(TENANT, WF);
        Instant actifAlertedAt = afterAlert.get("CMD-ACTIF").alertedAt();
        Instant litigeAlertedAt = afterAlert.get("CMD-LITIGE").alertedAt();
        assertThat(actifAlertedAt).isNotNull();
        assertThat(litigeAlertedAt).isNotNull();

        // Sweep 3 — silence: alerted_at is set and STAYS set (the gate, not the constraint,
        // is what keeps quiet), nothing re-emits.
        sweep.sweepNow();
        assertThat(dormantEvents()).hasSize(2);
        Map<String, WorkflowObservationStore.Observation> afterSilence = observations.loadAll(TENANT, WF);
        assertThat(afterSilence.get("CMD-ACTIF").alertedAt()).isEqualTo(actifAlertedAt);
        assertThat(afterSilence.get("CMD-LITIGE").alertedAt()).isEqualTo(litigeAlertedAt);

        // The sleeper moves: state change → transition row, alert flag reset…
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES ("
                + TENANT + ", 'CMD-ACTIF', 'fini')");
        }
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("CMD-ACTIF").state()).isEqualTo("TERMINE");
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM core.workflow_transition WHERE tenant_id = ? AND doc_key = 'CMD-ACTIF'")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); assertThat(rs.getLong(1)).isEqualTo(1); }
        }

        // …and TERMINE is terminal: age it, sweep — still only the LITIGE alert count stands.
        ageAll();
        sweep.sweepNow();
        assertThat(dormantEvents()).hasSize(2);
    }

    @Test
    void undeclared_state_casing_drift_is_not_mistaken_for_movement() throws Exception {
        // A dossier sleeping in an UNDECLARED state — the manifest never described it, which
        // is exactly the sickest case dormancy exists to catch. Dirty legacy data means the
        // same undeclared value can reappear with different casing/whitespace on a later
        // ingest ("EN LITIGE" then "en litige "). That must read as the same state, not a
        // transition — a raw-string comparison would reset `since` and the alert flag on
        // every such re-ingest, so the document could dodge dormancy detection forever.
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES ("
                + TENANT + ", 'CMD-CASSE', 'EN LITIGE')");
        }
        sweep.sweepNow(); // cold start for this key
        Instant since0 = observations.loadAll(TENANT, WF).get("CMD-CASSE").since();
        assertThat(since0).isNotNull();

        // Same undeclared value, different casing + trailing whitespace — a later ingest.
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES ("
                + TENANT + ", 'CMD-CASSE', 'en litige ')");
        }
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("CMD-CASSE").since()).isEqualTo(since0);
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM core.workflow_transition WHERE tenant_id = ? AND doc_key = 'CMD-CASSE'")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); assertThat(rs.getLong(1)).isEqualTo(0); }
        }

        // …and it still alerts once genuinely aged past the declared threshold — the drift
        // didn't get it stuck, it just wasn't mistaken for a fresh arrival.
        ageAll();
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("CMD-CASSE").alertedAt()).isNotNull();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM core.event_outbox WHERE tenant_id = ? AND entity_key = 'CMD-CASSE' "
                    + "AND event_type = 'decision.dormant.wf-sweep-it'")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); assertThat(rs.getLong(1)).isEqualTo(1); }
        }
    }

    /**
     * Two rows for the same business key from ONE ingest chunk. {@code _ingested_at} defaults to
     * {@code now()} — the TRANSACTION timestamp — so every row of a chunk carries the byte-identical
     * value, and {@code ORDER BY key, _ingested_at DESC} alone leaves DISTINCT ON free to pick
     * either. That freedom is not academic: the sweep would resolve TERMINE on one tick and
     * EN_COURS on the next, writing phantom transitions that never happened into the append-only
     * journal — the compounding asset — and the document would dodge dormancy forever.
     */
    @Test
    void two_versions_from_one_ingest_chunk_resolve_deterministically_and_write_no_phantoms() throws Exception {
        // ONE statement = one transaction = one `_ingested_at`. The second VALUES row is the
        // later-written version and therefore carries the higher id: that is the one that wins.
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES "
                + "(" + TENANT + ", 'CMD-DUP', 'fini'), (" + TENANT + ", 'CMD-DUP', 'en cours')");
        }

        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("CMD-DUP").state()).isEqualTo("EN_COURS");

        // …and it stays that state, tick after tick, with an empty tape for this document.
        sweep.sweepNow();
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("CMD-DUP").state()).isEqualTo("EN_COURS");
        assertThat(transitionCount("CMD-DUP")).isZero();
    }

    /**
     * A blank business key (empty string or whitespace-only) identifies nothing — there is no
     * key to follow the document by, so it must never surface a ledger row or an outbox event.
     * A normal row in the SAME ingest batch must still be processed: the blank rows are excluded,
     * not poisoning, the rest of the batch (docs/apps/workflows.md — "What the sweep does not see").
     */
    @Test
    void blank_business_key_never_produces_a_ledger_row_or_outbox_event() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES "
                + "(" + TENANT + ", '', 'en cours'),"
                + "(" + TENANT + ", '   ', 'en cours'),"
                + "(" + TENANT + ", 'CMD-BLANK-OK', 'en cours')");
        }
        sweep.sweepNow(); // cold start
        Map<String, WorkflowObservationStore.Observation> afterColdStart = observations.loadAll(TENANT, WF);
        assertThat(afterColdStart).doesNotContainKeys("", "   ");
        assertThat(afterColdStart).containsKey("CMD-BLANK-OK");

        ageAll();
        sweep.sweepNow();
        List<Map<String, String>> events = dormantEvents();
        assertThat(events).extracting(e -> e.get("key")).doesNotContain("", "   ");
        assertThat(events).extracting(e -> e.get("key")).contains("CMD-BLANK-OK");
    }

    /**
     * A doc key longer than {@code core.event_outbox.entity_key}/{@code dedup_key} (VARCHAR(200),
     * V24) must not poison the batch. The key is raw customer text of unbounded length; a 22001
     * escaping the per-document work would be caught only by the per-WORKFLOW handler, abandoning
     * every document the deterministic result order places after it — every tick, forever.
     */
    @Test
    void an_oversized_doc_key_still_alerts_and_never_abandons_the_rest_of_the_batch() throws Exception {
        String longKey = "CMD-LONG-" + "X".repeat(250);
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            // CMD-ZULU sorts AFTER the oversized key, so the ORDER BY puts it downstream of the poison
            s.execute("INSERT INTO " + TABLE + " (tenant_id, num, statut) VALUES "
                + "(" + TENANT + ", '" + longKey + "', 'en cours'),"
                + "(" + TENANT + ", 'CMD-ZULU', 'en cours')");
        }
        sweep.sweepNow();   // cold start
        ageAll();
        sweep.sweepNow();   // both are now overdue

        List<Map<String, String>> events = dormantEvents();
        // the document downstream of the poison key was still processed
        assertThat(events).extracting(e -> e.get("key")).contains("CMD-ZULU");
        // the oversized key alerted too — bounded in the VARCHAR(200) columns…
        assertThat(events).allSatisfy(e ->
            assertThat(e.get("key").length()).isLessThanOrEqualTo(200));
        // …while the payload (jsonb, unbounded) carries the real key verbatim: the operator
        // must still be able to see which document it is.
        assertThat(events).anySatisfy(e -> assertThat(e.get("payload")).contains(longKey));
        assertThat(observations.loadAll(TENANT, WF).get(longKey).alertedAt()).isNotNull();
    }

    /**
     * Every identifier in the sweep's source query is interpolated — JDBC cannot bind one — so this
     * is where quoting has to hold: a capitalised table, a space in the watched column, a {@code °}
     * in the business key. Bare, {@code SELECT DISTINCT ON (N° Commande)} is not a miss but a SYNTAX
     * ERROR, and the per-workflow net swallows it as a logged skip — a declared dormancy that never
     * fires, in silence, forever.
     *
     * <p>Which is exactly why the ledger is the whole assertion: a swallowed statement observes
     * nothing, so {@code containsOnlyKeys} is what goes red (and did, before the fix). Aging and
     * alerting past that point would re-prove {@code cold_start_then_dormancy…} with no identifier
     * in play. One tenant of its own, because this workflow emits like any other and the assertions
     * above count events per tenant.
     */
    @Test
    void a_lifecycle_over_the_names_the_source_chose_still_sweeps() throws Exception {
        long tenant = 7502L;
        String table = "connector.\"t_WfSweepVerbatim_it\"";
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-sweep-verbatim-it }
            spec:
              scope: demo
              provenance: declared
              on: { table: "connector.t_WfSweepVerbatim_it", column: "Statut Commande", key: "N° Commande" }
              dormancy: { after: 1m }
              states:
                - { code: EN_COURS, initial: true, aliases: [en cours] }
                - { code: TERMINE, aliases: [fini], terminal: true }
            """;
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGSERIAL PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, \"N° Commande\" TEXT, \"Statut Commande\" TEXT, "
                + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            s.execute("DELETE FROM " + table);
            s.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + tenant);
            s.execute("INSERT INTO " + table + " (tenant_id, \"N° Commande\", \"Statut Commande\") "
                + "VALUES (" + tenant + ", 'CMD-1', 'en cours'), (" + tenant + ", 'CMD-2', 'fini')");
        }
        manifests.upsert(tenant, new WorkflowManifestParser().parse(yaml), yaml, null);

        sweep.sweepNow();
        assertThat(observations.loadAll(tenant, "wf-sweep-verbatim-it"))
            .containsOnlyKeys("CMD-1", "CMD-2");
    }

    /**
     * One stored manifest the parser no longer accepts must cost the sweep that row and nothing else
     * (#807). {@code listByTenant} orders by name, so {@code aaa-garbled} is read BEFORE the good
     * workflow of the same tenant: with the strict read the exception left the registry before it
     * returned anything, and the good one — and every tenant after it — was never swept.
     *
     * <p>Both manifests this test adds are deleted afterwards, ledger included: the table is shared, and a row left behind is how
     * this showed up in #806 — it failed every other sweep test in the gate.
     */
    @Test
    void a_manifest_that_no_longer_parses_costs_only_its_own_row_not_the_sweep() throws Exception {
        long other = 7503L;
        String table = "connector.t_wf_sweep_garbled_it";
        String yaml = YAML.replace("wf-sweep-it", "wf-sweep-garbled-it")
            .replace("connector.t_wf_sweep_it", table);
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGSERIAL PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, num TEXT, statut TEXT, "
                + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            s.execute("DELETE FROM " + table);
            s.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + other);
            s.execute("INSERT INTO " + table + " (tenant_id, num, statut) VALUES ("
                + other + ", 'CMD-OTHER', 'en cours')");
        }
        manifests.upsert(other, new WorkflowManifestParser().parse(yaml), yaml, null);
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core.workflow_manifest (tenant_id, name, yaml_text, scope, on_table, on_column) "
                    + "VALUES (?, 'aaa-garbled', 'spec: [', 'demo', 'connector.t_garbled', 'statut')")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        }
        try {
            sweep.sweepNow();

            assertThat(observations.loadAll(TENANT, WF)).containsKeys("CMD-ACTIF", "CMD-LITIGE", "CMD-FINI");
            assertThat(observations.loadAll(other, "wf-sweep-garbled-it")).containsOnlyKeys("CMD-OTHER");
        } finally {
            // delete() takes the observation ledger and the tape with the manifest, for both rows
            manifests.delete(TENANT, "aaa-garbled");
            manifests.delete(other, "wf-sweep-garbled-it");
        }
    }
}
