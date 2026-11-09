package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.sql.ManagedIndex.Spec;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1834: a change in a LOOKUP table ({@code joins:}) of a {@code match:} Entity re-evaluates every fused row the lookup
 * row reaches — here, a recalibration in the instrument register re-evaluates every result of that instrument. The
 * fan-out is durable work, not part of the event: the consumer writes one work row and moves on, a worker drains it a
 * page of keys at a time.
 *
 * <p>The scheduled worker is off in the test profile ({@code lumnik.process.fanout.every=off}); the tests drive it
 * page by page, which is what makes "a crash between two pages" something they can write down.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class LookupFanOutIT {

    private static final String TYPE = "decision.lf-expired";
    private static final String VIEW = "connector.v_lfresult";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject LookupFanOut fanOut;
    @Inject MatchIndexes matchIndexes;

    private static final String ENTITY = """
        kind: Entity
        name: LfResult
        match: [result_id]
        scopes: [lf]
        sources:
          - table: t_lf_results
            map: { result_id: result_id, instrument_id: instrument_id, measured_at: measured_at }
        joins:
          - table: t_lf_instruments
            from: instrument_id
            to: instrument
            map: { calib_valid_until: calib_valid_until }
        """;

    private static final String PROCESS = """
        apiVersion: apps.lumnik.io/v1
        kind: Process
        metadata: { name: lf-expired }
        spec:
          scope: lf
          on: { event: data.row.changed, table: connector.v_lfresult }
          when: [ "measured_at>{calib_valid_until}" ]
          emit: { type: decision.lf-expired, message: "{result_id} was measured after the calibration ran out", key: result_id }
        """;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String text(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("DELETE FROM core.process_manifest WHERE name = 'lf-expired'");   // cascades to its work rows
        exec("DELETE FROM core.event_outbox WHERE source = 'it-lf' OR event_type = '" + TYPE + "'");
        exec("DELETE FROM connector.dynamic_table WHERE mapping_id IN ('lf-results', 'lf-results2', 'lf-instruments')");
        exec("DROP VIEW IF EXISTS connector.\"v_lfresult\"");
        exec("DROP TABLE IF EXISTS connector.t_lf_results2");
        exec("DROP TABLE IF EXISTS connector.t_lf_results");
        exec("DROP TABLE IF EXISTS connector.t_lf_instruments");
        exec("CREATE TABLE connector.t_lf_results (tenant_id bigint NOT NULL, _row_hash text, result_id text, instrument_id text, measured_at timestamptz)");
        exec("CREATE TABLE connector.t_lf_instruments (tenant_id bigint NOT NULL, _row_hash text, instrument text, calib_valid_until timestamptz)");
        // tenant 1: five results on INST-1, two on INST-2, one with no instrument; tenant 2 holds a result of its own on INST-1
        exec("INSERT INTO connector.t_lf_results VALUES "
           + "(1, 'r1', 'R-1', 'INST-1', '2026-06-01Z'), (1, 'r2', 'R-2', 'INST-1', '2026-06-01Z'), (1, 'r3', 'R-3', 'INST-1', '2026-06-01Z'),"
           + "(1, 'r4', 'R-4', 'INST-1', '2026-06-01Z'), (1, 'r5', 'R-5', 'INST-1', '2026-06-01Z'),"
           + "(1, 'r6', 'R-6', 'INST-2', '2026-06-01Z'), (1, 'r7', 'R-7', 'INST-2', '2026-06-01Z'), (1, 'r8', 'R-8', NULL, '2026-06-01Z'),"
           + "(2, 'x1', 'X-1', 'INST-1', '2026-06-01Z')");
        exec("INSERT INTO connector.t_lf_instruments VALUES "
           + "(1, 'i1', 'INST-1', '2027-01-01Z'), (1, 'i2', 'INST-2', '2027-01-01Z'), (1, 'i0', NULL, '2027-01-01Z'),"
           + "(2, 'j1', 'INST-1', '2027-01-01Z')");
        for (String t : new String[] {"t_lf_results", "t_lf_instruments"}) {
            exec("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
            exec("CREATE POLICY tenant_isolation ON connector." + t
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            exec("GRANT SELECT ON connector." + t + " TO lumnik_app, lumnik_readonly");
            for (int tenant : new int[] {1, 2}) {
                exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                   + "VALUES (" + tenant + ", '" + t.replace("t_", "").replace('_', '-') + "', 'connector', '" + t + "', '{}'::jsonb)");
            }
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply").then().statusCode(200);
        for (int tenant : new int[] {1, 2}) {
            exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES ("
               + tenant + ", 'lf-expired', '" + PROCESS.replace("'", "''") + "', 'lf', '" + VIEW + "', '" + TYPE + "')");
        }
        buildTheReverseIndex();
    }

    private EntityManifest entity() {
        return matchIndexes.storedEntity(VIEW).orElseThrow();
    }

    private List<Spec> reverseIndexes() {
        return matchIndexes.reverse(entity());
    }

    private void buildTheReverseIndex() {
        matchIndexes.ensureReverse(reverseIndexes());
    }

    private void dropTheReverseIndex() {
        for (Spec s : reverseIndexes()) exec("DROP INDEX IF EXISTS " + s.schema() + ".\"" + s.name() + "\"");
    }

    /** The lookup row of {@code instrument} is recalibrated: its register entry now ran out before the results were taken. */
    private void recalibrate(int tenant, String instrument, String newHash) {
        exec("UPDATE connector.t_lf_instruments SET calib_valid_until = '2026-01-01Z', _row_hash = '" + newHash
           + "' WHERE tenant_id = " + tenant + " AND instrument = '" + instrument + "'");
        change(tenant, "t_lf_instruments", newHash);
    }

    private void change(int tenant, String table, String hash) {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (" + tenant + ", 'data.row.changed', 'it-lf', 'connector." + table + "', '" + hash + "', "
           + "'{\"op\":\"update\"}'::jsonb, NOW(), NOW())");
    }

    private void awaitSwept() {
        long max = scalar("SELECT COALESCE(MAX(id), 0) FROM core.event_outbox");
        await().atMost(25, SECONDS).untilAsserted(() ->
            assertThat(scalar("SELECT COALESCE(MAX(last_event_id), 0) FROM core.event_consumer WHERE consumer_name = 'process'"))
                .isGreaterThanOrEqualTo(max));
    }

    private List<String> decidedKeys(int tenant) {
        List<String> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT entity_key FROM core.event_outbox WHERE tenant_id = ? AND event_type = ? ORDER BY entity_key, id")) {
            ps.setInt(1, tenant);
            ps.setString(2, TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    private long workRows(int tenant) {
        return scalar("SELECT count(*) FROM core.process_fanout WHERE tenant_id = " + tenant);
    }

    /** Scenario of #1811: every result of the instrument is re-evaluated, none of another's, none of another tenant's. */
    @Test
    void aRecalibrationReEvaluatesEveryResultOfTheInstrument() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();

        assertThat(workRows(1)).as("the consumer writes one work row and moves on").isEqualTo(1);
        assertThat(decidedKeys(1)).as("nothing is decided inside the event").isEmpty();

        fanOut.drain(500);

        assertThat(decidedKeys(1)).containsExactly("R-1", "R-2", "R-3", "R-4", "R-5");
        assertThat(decidedKeys(2)).as("tenant 2 holds INST-1 too: its rows are never reached").isEmpty();
        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("done");
        assertThat(scalar("SELECT affected_count FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo(5);
        assertThat(scalar("SELECT processed_count FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo(5);
    }

    /** More than one page, and a crash between two: nothing twice, nothing lost. */
    @Test
    void aCrashBetweenTwoPagesLosesNoKeyAndRepeatsNone() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();

        assertThat(fanOut.page(2)).isTrue();   // R-1, R-2
        assertThat(decidedKeys(1)).containsExactly("R-1", "R-2");
        assertThat(text("SELECT after_canonical_key FROM core.process_fanout WHERE tenant_id = 1")).isNotNull();
        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("running");

        // the node dies here; nothing in memory survives. Another one (or the next tick) takes the row up where it stopped.
        fanOut.drain(2);

        assertThat(decidedKeys(1)).containsExactly("R-1", "R-2", "R-3", "R-4", "R-5");
        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("done");
        assertThat(scalar("SELECT processed_count FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo(5);
    }

    /** A page replayed (its transaction committed, the node died before saying so): the dedup key makes it harmless. */
    @Test
    void aReplayedPageEmitsNothingTwice() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();
        fanOut.drain(2);
        assertThat(decidedKeys(1)).hasSize(5);

        exec("UPDATE core.process_fanout SET status = 'pending', after_canonical_key = NULL, processed_count = 0 WHERE tenant_id = 1");
        fanOut.drain(2);

        assertThat(decidedKeys(1)).containsExactly("R-1", "R-2", "R-3", "R-4", "R-5");
    }

    @Test
    void aWorkRowWaitsForTheIndexThenDrains() {
        dropTheReverseIndex();
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();

        fanOut.drain(500);

        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("waiting-index");
        assertThat(decidedKeys(1)).as("nothing is lost, nothing is read without the index").isEmpty();

        buildTheReverseIndex();
        fanOut.drain(500);

        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("done");
        assertThat(decidedKeys(1)).hasSize(5);
    }

    @Test
    void aSourceChangeStaysInlineAndWritesNoWorkRow() {
        change(1, "t_lf_results", "r1");
        awaitSwept();

        assertThat(workRows(1)).isZero();
    }

    /** A lookup row without a key reaches nothing: no work row, no error; the event after it is still served. */
    @Test
    void aLookupRowWithoutAKeyReachesNothing() {
        change(1, "t_lf_instruments", "i0");
        recalibrate(1, "INST-2", "i2-v2");
        awaitSwept();

        assertThat(workRows(1)).isEqualTo(1);
        fanOut.drain(500);
        assertThat(decidedKeys(1)).containsExactly("R-6", "R-7");
    }

    /** The same event seen twice (a cursor rewound): one work row, one set of decisions. */
    @Test
    void aReplayedEventWritesOneWorkRow() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();
        exec("UPDATE core.event_consumer SET last_xact_id = 0, last_event_id = 0 WHERE consumer_name = 'process'");
        awaitSwept();

        assertThat(workRows(1)).isEqualTo(1);
        fanOut.drain(500);
        assertThat(decidedKeys(1)).hasSize(5);
    }

    /**
     * One lookup read from two sources is two targets — two work rows. (An Entity cannot join one table twice: a
     * lookup event matches several targets through the several sources that carry the join's {@code from}.) A fused row
     * both sources carry is reached twice and decided once: the dedup key is per (event, key).
     */
    @Test
    void aLookupReadFromTwoSourcesIsTwoTargets() {
        exec("DELETE FROM core.process_manifest WHERE name = 'lf-expired'");
        exec("DROP TABLE IF EXISTS connector.t_lf_results2");
        exec("CREATE TABLE connector.t_lf_results2 (tenant_id bigint NOT NULL, _row_hash text, result_id text, instrument_id text, measured_at timestamptz)");
        exec("INSERT INTO connector.t_lf_results2 VALUES (1, 'q1', 'R-1', 'INST-1', '2026-06-01Z'), (1, 'q9', 'R-9', 'INST-1', '2026-06-01Z')");
        exec("ALTER TABLE connector.t_lf_results2 ENABLE ROW LEVEL SECURITY");
        exec("CREATE POLICY tenant_isolation ON connector.t_lf_results2"
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        exec("GRANT SELECT ON connector.t_lf_results2 TO lumnik_app, lumnik_readonly");
        for (int tenant : new int[] {1, 2}) {
            exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
               + "VALUES (" + tenant + ", 'lf-results2', 'connector', 't_lf_results2', '{}'::jsonb)");
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body("""
                kind: Entity
                name: LfResult
                match: [result_id]
                scopes: [lf]
                sources:
                  - table: t_lf_results
                    map: { result_id: result_id, instrument_id: instrument_id, measured_at: measured_at }
                  - table: t_lf_results2
                    map: { result_id: result_id, instrument_id: instrument_id, measured_at: measured_at }
                joins:
                  - table: t_lf_instruments
                    from: instrument_id
                    to: instrument
                    map: { calib_valid_until: calib_valid_until }
                """).post("/api/entities/apply").then().statusCode(200);
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES (1, 'lf-expired', '"
           + PROCESS.replace("'", "''") + "', 'lf', '" + VIEW + "', '" + TYPE + "')");
        buildTheReverseIndex();

        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();

        assertThat(workRows(1)).isEqualTo(2);
        fanOut.drain(500);
        assertThat(decidedKeys(1)).containsExactly("R-1", "R-2", "R-3", "R-4", "R-5", "R-9");
    }

    /** A lookup change of one tenant never reaches another's rows, even for the same key and the same canonical row key. */
    @Test
    void aTenantsLookupChangeNeverReachesAnotherTenant() {
        recalibrate(2, "INST-1", "j1-v2");
        awaitSwept();
        fanOut.drain(500);

        assertThat(decidedKeys(2)).containsExactly("X-1");
        assertThat(decidedKeys(1)).isEmpty();
    }

    /**
     * The handoff from the outbox to the work table, through the real sweeper: the sweeper moves the cursor past an event
     * whatever the consumer threw, so a work row that cannot be written once must not be the end of the change.
     * The first insert into the table fails (a trigger fed by a sequence, which no rollback undoes); the next one lands.
     */
    @Test
    void aFirstFailedEnqueueStillLeavesTheWorkAndExactlyOneDecisionEach() {
        exec("DROP SEQUENCE IF EXISTS core.it_fanout_fail");
        exec("CREATE SEQUENCE core.it_fanout_fail");
        exec("CREATE OR REPLACE FUNCTION core.it_fanout_fail() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN "
           + "IF nextval('core.it_fanout_fail') = 1 THEN RAISE EXCEPTION 'the first enqueue fails'; END IF; RETURN NEW; END $f$");
        exec("CREATE TRIGGER it_fanout_fail BEFORE INSERT ON core.process_fanout FOR EACH ROW EXECUTE FUNCTION core.it_fanout_fail()");
        try {
            recalibrate(1, "INST-1", "i1-v2");
            awaitSwept();

            assertThat(workRows(1)).as("the work row exists although the first insert failed").isEqualTo(1);
            fanOut.drain(500);
            assertThat(decidedKeys(1)).containsExactly("R-1", "R-2", "R-3", "R-4", "R-5");
        } finally {
            exec("DROP TRIGGER IF EXISTS it_fanout_fail ON core.process_fanout");
            exec("DROP FUNCTION IF EXISTS core.it_fanout_fail()");
            exec("DROP SEQUENCE IF EXISTS core.it_fanout_fail");
        }
    }

    /** The Entity was re-applied without the join: the queued work has no meaning any more, and the row says so — it is not retried for ever. */
    @Test
    void workWhoseJoinWasRemovedFailsWithAReason() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();
        exec("UPDATE connector.entity_manifest SET yaml_text = $y$kind: Entity\nname: LfResult\nmatch: [result_id]\nscopes: [lf]\n"
           + "sources:\n  - table: t_lf_results\n    map: { result_id: result_id, instrument_id: instrument_id, measured_at: measured_at }\n$y$"
           + " WHERE name = 'lfresult'");

        fanOut.drain(500);

        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("failed");
        assertThat(text("SELECT last_error FROM core.process_fanout WHERE tenant_id = 1")).contains("no longer joins");
        assertThat(decidedKeys(1)).isEmpty();
    }

    /** A process switched off does not decide; its work waits and resumes when it is switched on again. */
    @Test
    void aDisabledProcessKeepsItsWorkWaiting() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();
        exec("UPDATE core.process_manifest SET enabled = false WHERE tenant_id = 1 AND name = 'lf-expired'");

        fanOut.drain(500);
        assertThat(decidedKeys(1)).isEmpty();
        assertThat(text("SELECT status FROM core.process_fanout WHERE tenant_id = 1")).isEqualTo("pending");

        exec("UPDATE core.process_manifest SET enabled = true WHERE tenant_id = 1 AND name = 'lf-expired'");
        fanOut.drain(500);
        assertThat(decidedKeys(1)).hasSize(5);
    }

    /** Removing the process takes its pending work with it: nothing is evaluated for a process that is gone. */
    @Test
    void deletingTheProcessDropsItsWork() {
        recalibrate(1, "INST-1", "i1-v2");
        awaitSwept();
        assertThat(workRows(1)).isEqualTo(1);

        exec("DELETE FROM core.process_manifest WHERE tenant_id = 1 AND name = 'lf-expired'");

        assertThat(workRows(1)).isZero();
        fanOut.drain(500);
        assertThat(decidedKeys(1)).isEmpty();
    }
}
