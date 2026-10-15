package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assembled-system proof of #683, written once and run over both managed destinations: every
 * scenario drives the real runner (or the real scheduler) end to end — stored definition, tenant
 * binding, RLS-enforced snapshot read, CSV, destination commit, ledger, audit — and then inspects
 * what actually reached the remote side. Each invariant here has a unit or component test of its
 * own elsewhere; what these add is that the pieces still hold it once they are assembled.
 */
abstract class ExportAcceptanceScenarios extends ExportAcceptanceITSupport {

    static final String HEADER = "\"code\",\"name\"";

    static byte[] csv(String header, String... rows) {
        StringBuilder s = new StringBuilder(header).append("\r\n");
        for (String row : rows) s.append(row).append("\r\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tenant

    @Test
    void onlyTheRunningTenantsRowsLeaveTheHub() throws Exception {
        seedFor(OTHER_TENANT, "('15', 'Eve'), ('25', 'Fay')");
        String name = unique("tenancy");
        String mineTarget = name + "-t1.csv";
        String theirsTarget = name + "-t2.csv";
        // The same export name in both tenants: two independent definitions, never one shared.
        applyAs(TENANT, name, "[code, name]", "[code]", mineTarget, false, "");
        applyAs(OTHER_TENANT, name, "[code, name]", "[code]", theirsTarget, false, "");

        ExportRunView mine = awaitAs(TENANT, name, launchAs(TENANT, name, AT));
        ExportRunView theirs = awaitAs(OTHER_TENANT, name, launchAs(OTHER_TENANT, name, AT));

        byte[] mineCsv = csv(HEADER, "\"1\",\"Ann\"", "\"2\",\"Bob\"", "\"3\",\"Cid\"");
        byte[] theirsCsv = csv(HEADER, "\"15\",\"Eve\"", "\"25\",\"Fay\"");
        assertThat(mine.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(remote(mineTarget)).isEqualTo(mineCsv);
        assertThat(mine.rowCount()).isEqualTo(3L);
        assertThat(mine.sha256()).isEqualTo(sha256(mineCsv));
        assertThat(theirs.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(remote(theirsTarget)).isEqualTo(theirsCsv);
        assertThat(theirs.rowCount()).isEqualTo(2L);
        assertThat(theirs.sha256()).isEqualTo(sha256(theirsCsv));

        // Neither tenant can read the other's run, even naming its id.
        assertThat(find(OTHER_TENANT, name, mine.id())).isEmpty();
        assertThat(find(TENANT, name, theirs.id())).isEmpty();
        // A definition authorized for tenant 1 cannot be executed as tenant 2.
        ExportRunRequest asOther = new ExportRunRequest(OTHER_TENANT, name, ExportTrigger.MANUAL, 7L,
            null, AT, 1, null);
        assertThatThrownBy(() -> runner.launch(asOther, row(name)))
            .isInstanceOf(IllegalArgumentException.class);
        // An export only tenant 1 declares does not exist for tenant 2.
        String onlyMine = applyStandard(unique("only-mine") + ".csv");
        assertThat(runner.start(ExportRunRequest.manual(OTHER_TENANT, onlyMine, 7L)))
            .isEqualTo(new StartResult.NotFound());
        assertThat(runRows(onlyMine)).isZero();
        // Each EGRESS is recorded against the tenant whose rows left, with that artifact's hash.
        assertThat(Jdbc.query(adminDs, "egress tenants",
            "SELECT tenant_id || ' ' || (changes->>'sha256') FROM core.audit_outbox "
          + "WHERE entity_key = ? AND operation = 'EGRESS'",
            ps -> ps.setString(1, name), rs -> rs.getString(1)))
            .containsExactlyInAnyOrder(TENANT + " " + sha256(mineCsv),
                OTHER_TENANT + " " + sha256(theirsCsv));
    }

    @Test
    void aTenantCannotResolveAnotherTenantsSecret() throws Exception {
        String name = unique("borrowed");
        String target = name + ".csv";
        applyAs(OTHER_TENANT, name, "[code, name]", "[code]", target, false, "");
        // Tenant 2 no longer holds the credential; tenant 1 still does, under the same name.
        for (String secret : secrets().keySet())
            given().headers(TestIdentity.tenantAndUser(OTHER_TENANT))
                .delete("/api/platform/secrets/" + secret)
                .then().statusCode(204);

        ExportRunView run = awaitAs(OTHER_TENANT, name, launchAs(OTHER_TENANT, name, AT));

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.PUBLICATION);
        assertThat(remote(target)).isNull();
        assertNoLeftover(target);
        for (String value : secrets().values()) assertThat(run.failureReason()).doesNotContain(value);
    }

    @Test
    void aNonPositiveTenantIsRefusedBeforeAnythingMoves() throws Exception {
        String target = unique("tenantless") + ".csv";
        String name = applyStandard(target);
        ExportManifestStore.Row stored = row(name);

        for (long tenant : new long[] {0L, -1L}) {
            // The runner: an unbound tenant finds no definition, and cannot borrow tenant 1's.
            assertThat(runner.start(ExportRunRequest.manual(tenant, name, 7L)))
                .as("start as tenant %d", tenant).isEqualTo(new StartResult.NotFound());
            ExportRunRequest request = new ExportRunRequest(tenant, name, ExportTrigger.MANUAL, 7L,
                null, AT, 1, null);
            assertThatThrownBy(() -> runner.launch(request, stored))
                .isInstanceOf(IllegalArgumentException.class);
            // The door: no positive tenant, no run.
            given().headers(TestIdentity.tenantAndUser(tenant))
                .post("/api/exports/" + name + "/runs")
                .then().statusCode(401);
        }
        given().post("/api/exports/" + name + "/runs").then().statusCode(401);

        // The scheduler: a stored definition carrying a non-positive tenant is never executed.
        String orphan = unique("orphan");
        Jdbc.update(adminDs, "orphan " + orphan,
            "INSERT INTO connector.export_manifest (tenant_id, name, scope, entity_name, "
          + "destination_kind, schedule, yaml_text, created_at) "
          + "SELECT 0, ?, scope, entity_name, destination_kind, ?, "
          + "replace(yaml_text, name, ?), '2000-01-01T00:00:00Z' "
          + "FROM connector.export_manifest WHERE tenant_id = ? AND name = ?",
            ps -> {
                ps.setString(1, orphan);
                ps.setString(2, "0 4 1 1 *");
                ps.setString(3, orphan);
                ps.setLong(4, TENANT);
                ps.setString(5, name);
            });
        try {
            scheduler.tick(Instant.parse("2099-01-01T04:01:30Z"));
            assertThat(runRows(orphan)).isZero();
        } finally {
            Jdbc.update(adminDs, "drop orphan", "DELETE FROM connector.export_manifest WHERE name = ?",
                ps -> ps.setString(1, orphan));
        }

        assertThat(runRows(name)).isZero();
        assertThat(remote(target)).isNull();
        assertThat(egress(name)).isEmpty();
    }

    // ------------------------------------------------------------------ scope

    @Test
    void aScheduledRunReadsUnderTheStoredScopeAndRefusesOnceTheEntityDropsIt() throws Exception {
        String name = unique("scheduled");
        String template = name + "-${date}.csv";
        applyAs(TENANT, name, "[code, name]", "[code]", template, false,
            "  schedule: \"0 5 1 1 *\"");
        try {
            scheduler.tick(Instant.parse("2099-01-01T05:01:30Z"));
            List<String[]> first = settled(name);

            // No request, no identity, no roles: the run's scope is the stored definition's.
            assertThat(first).hasSize(1);
            assertThat(first.getFirst()).containsExactly(ExportRunStatus.COMPLETED.sqlValue(), null,
                ExportTrigger.SCHEDULE.sqlValue(), null, "sales", String.valueOf(TENANT));
            assertThat(remote(name + "-2099-01-01.csv")).isEqualTo(local().bytes());
            assertThat(Jdbc.query(adminDs, "egress actor",
                "SELECT coalesce(actor_id::text, 'none') || ' ' || (changes->>'scope') "
              + "FROM core.audit_outbox WHERE entity_key = ? AND operation = 'EGRESS'",
                ps -> ps.setString(1, name), rs -> rs.getString(1)))
                .containsExactly("none sales");

            // The entity stops declaring 'sales': the next occurrence refuses rather than widen.
            applyEntity("ops");
            scheduler.tick(Instant.parse("2100-01-01T05:01:30Z"));
            List<String[]> both = settled(name);

            assertThat(both).hasSize(2)
                .anySatisfy(r -> assertThat(r).startsWith(ExportRunStatus.FAILED.sqlValue(),
                    ExportFailureCode.DEFINITION.sqlValue(), ExportTrigger.SCHEDULE.sqlValue()));
            assertThat(remote(name + "-2100-01-01.csv")).isNull();
            assertNoLeftover(name + "-2100-01-01.csv");
            assertThat(egress(name)).hasSize(1);
        } finally {
            // Unscheduled again, so no later tick in this app picks it up.
            unschedule(name);
        }
    }

    // ------------------------------------------------------------------ projection

    @Test
    void theArtifactCarriesExactlyTheDeclaredProjection() throws Exception {
        String name = unique("projection");
        String target = name + ".csv";
        applyAs(TENANT, name, "[name, code]", "[code]", target, false, "");

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        // Declared order, declared columns only — while the view itself carries more.
        assertThat(remote(target)).isEqualTo(
            csv("\"name\",\"code\"", "\"Ann\",\"1\"", "\"Bob\",\"2\"", "\"Cid\",\"3\""));
        assertThat(viewColumns()).contains("code", "name", "_conflicts");
    }

    @Test
    void anInternalColumnNeverLeavesEvenPastTheApplyDoor() throws Exception {
        // The apply door refuses each internal column by name.
        for (String internal : List.of("_conflicts", "_entity_id", "_resolution")) {
            String refused = unique("internal");
            given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(TENANT))
                .contentType("application/yaml")
                .body(exportYaml(refused, "[code, " + internal + "]", "[code]", refused + ".csv",
                    false, ""))
                .post("/api/exports/apply")
                .then().statusCode(400);
            assertThat(Jdbc.queryOne(adminDs, "stored " + refused,
                "SELECT 1 FROM connector.export_manifest WHERE name = ?",
                ps -> ps.setString(1, refused), rs -> rs.getInt(1))).isEmpty();
        }

        // A stored definition tampered with behind the door is refused by the run's own re-check.
        String target = unique("tampered") + ".csv";
        String name = applyStandard(target);
        int changed = Jdbc.update(adminDs, "tamper " + name,
            "UPDATE connector.export_manifest SET yaml_text = "
          + "replace(yaml_text, 'columns: [code, name]', 'columns: [code, name, _conflicts]') "
          + "WHERE tenant_id = ? AND name = ?",
            ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, name);
            });
        assertThat(changed).isEqualTo(1);

        ExportRunView run = await(name, runner.launch(
            new ExportRunRequest(TENANT, name, ExportTrigger.MANUAL, 7L, null, AT, 1, null)));

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.DEFINITION);
        assertThat(run.failureReason()).contains("_conflicts");
        assertThat(remote(target)).isNull();
        assertNoLeftover(target);
        assertThat(egress(name)).isEmpty();
    }

    List<String> viewColumns() {
        return Jdbc.query(adminDs, "columns " + view(),
            "SELECT column_name FROM information_schema.columns "
          + "WHERE table_schema = 'connector' AND table_name = ?",
            ps -> ps.setString(1, view()), rs -> rs.getString(1));
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void runsOverUnchangedDataAreByteIdentical() throws Exception {
        bulk(2_000);
        String name = unique("identical");
        String template = name + "-${timestamp}.csv";
        applyAs(TENANT, name, "[code, name]", "[code]", template, false, "");
        Instant later = AT.plusSeconds(3_600);

        ExportRunView first = run(name, AT);
        ExportRunView second = run(name, later);

        byte[] firstBytes = remote(ArtifactName.resolve(template, AT));
        byte[] secondBytes = remote(ArtifactName.resolve(template, later));
        assertThat(first.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(first.location()).isNotEqualTo(second.location());
        assertThat(secondBytes).isEqualTo(firstBytes);
        assertThat(first.sha256()).isEqualTo(second.sha256()).isEqualTo(sha256(firstBytes));
        assertThat(first.byteCount()).isEqualTo(second.byteCount()).isEqualTo(firstBytes.length);
        assertThat(first.rowCount()).isEqualTo(second.rowCount()).isEqualTo(2_003L);
    }

    // ------------------------------------------------------------------ atomic publication

    @Test
    void aRunBrokenMidStreamNeverExposesItsArtifactAndItsRetryIsIdentical() throws Exception {
        bulk(rowsToOutlastTheHold());
        String target = unique("broken") + ".csv";
        String name = applyStandard(target);

        holdMidUpload();
        ExportRunner.Launch launch;
        try {
            launch = launchAs(TENANT, name, AT);
            awaitHeld();
            UUID runId = ((StartResult.Started) launch.result()).runId();

            // In flight: bytes have left the hub, yet no consumer can see a final artifact.
            assertThat(find(TENANT, name, runId).orElseThrow().status())
                .isEqualTo(ExportRunStatus.RUNNING);
            assertInFlight(target);
            assertThat(remote(target)).isNull();

            terminateSnapshotRead();
        } finally {
            relay().release();
        }
        ExportRunView failed = awaitAs(TENANT, name, launch);

        assertThat(failed.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo(ExportFailureCode.GENERATION);
        assertThat(failed.location()).isNull();
        assertThat(failed.sha256()).isNull();
        for (String value : secrets().values()) assertThat(failed.failureReason()).doesNotContain(value);
        assertThat(remote(target)).isNull();
        assertNoLeftover(target);
        assertThat(egress(name)).isEmpty();

        // The retry publishes, under the same name, exactly what an unbroken snapshot is.
        ExportRunView retried = retry(name, failed);
        Local local = local();
        byte[] delivered = remote(target);
        assertThat(retried.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(retried.retryOf()).isEqualTo(failed.id());
        assertThat(delivered).isEqualTo(local.bytes());
        assertThat(retried.sha256()).isEqualTo(local.stats().sha256Hex()).isEqualTo(sha256(delivered));
        assertNoLeftover(target);
        assertThat(egress(name)).hasSize(1);
    }

    @Test
    void anArtifactLumnikDidNotWriteIsNeverOverwritten() throws Exception {
        String target = unique("foreign") + ".csv";
        byte[] foreign = "written by someone else\n".getBytes(StandardCharsets.UTF_8);
        placeForeign(target, foreign);
        String name = applyStandard(target);

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.COLLISION);
        assertThat(run.location()).isNull();
        assertThat(remote(target)).isEqualTo(foreign);
        assertNoLeftover(target);
        assertThat(egress(name)).isEmpty();
    }

    /**
     * The hub dies between the destination commit and the ledger write: the artifact exists, the
     * reaper records the run {@code Failed / interrupted}. Its retry resolves the same name and
     * must refuse rather than replace what was already delivered — even though the data changed.
     */
    @Test
    void aRetryAfterACrashBetweenCommitAndReceiptNeverOverwrites() throws Exception {
        String target = unique("crashed") + ".csv";
        String name = applyStandard(target);
        ExportRunView delivered = run(name, AT);
        assertThat(delivered.status()).isEqualTo(ExportRunStatus.COMPLETED);
        byte[] published = remote(target);
        // What the reaper leaves behind for a run whose receipt was never written.
        Jdbc.update(adminDs, "crash " + delivered.id(),
            "UPDATE connector.export_run SET status = 'Failed', failure_code = 'interrupted', "
          + "failure_reason = 'hub stopped', row_count = NULL, byte_count = NULL, sha256 = NULL, "
          + "location = NULL, version_id = NULL, etag = NULL WHERE id = ?",
            ps -> ps.setObject(1, delivered.id()));
        seed("('4', 'Dee')");

        ExportRunView retried = retry(name, find(TENANT, name, delivered.id()).orElseThrow());

        assertThat(retried.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(retried.failureCode()).isEqualTo(ExportFailureCode.COLLISION);
        assertThat(retried.retryOf()).isEqualTo(delivered.id());
        assertThat(remote(target)).isEqualTo(published);
        assertNoLeftover(target);
    }

    // ------------------------------------------------------------------ concurrency

    /**
     * Four manual triggers and a due scheduler tick, released together on one literal artifact
     * name. However they interleave — lock refusals, a later start colliding with an earlier
     * delivery — exactly one run is Completed, one EGRESS is recorded and the remote bytes are the
     * snapshot's.
     */
    @Test
    void racingTriggersDeliverTheArtifactExactlyOnce() throws Exception {
        String name = unique("race");
        String target = name + ".csv";
        applyAs(TENANT, name, "[code, name]", "[code]", target, false, "  schedule: \"0 4 1 1 *\"");
        int manual = 4;
        CyclicBarrier start = new CyclicBarrier(manual + 1);
        List<Callable<Object>> triggers = new ArrayList<>();
        for (int i = 0; i < manual; i++)
            triggers.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                return runner.start(ExportRunRequest.manual(TENANT, name, 7L));
            });
        triggers.add(() -> {
            start.await(30, TimeUnit.SECONDS);
            return scheduler.tick(Instant.parse("2099-01-01T04:01:30Z"));
        });

        ExecutorService pool = Executors.newFixedThreadPool(triggers.size());
        try {
            for (Future<Object> f : pool.invokeAll(triggers)) f.get(60, TimeUnit.SECONDS);
            List<String[]> rows = settled(name);

            assertThat(rows).filteredOn(r -> r[0].equals(ExportRunStatus.COMPLETED.sqlValue()))
                .hasSize(1);
            assertThat(rows).filteredOn(r -> !r[0].equals(ExportRunStatus.COMPLETED.sqlValue()))
                .allSatisfy(r -> assertThat(r).startsWith(ExportRunStatus.FAILED.sqlValue(),
                    ExportFailureCode.COLLISION.sqlValue()));
            assertThat(remote(target)).isEqualTo(local().bytes());
            assertNoLeftover(target);
            assertThat(egress(name)).hasSize(1);
        } finally {
            pool.shutdownNow();
            unschedule(name);
        }
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void noCredentialLeavesThroughAnyChannel() throws Exception {
        String name = unique("secrets");
        String target = name + ".csv";
        applyAs(TENANT, name, "[code, name]", "[code]", target, false, "");
        List<ExportRunView> runs = new ArrayList<>();

        List<String> logs = logged(() -> {
            runs.add(run(name, AT));                                   // delivered
            runs.add(run(name, AT));                                   // collision
            applyAs(TENANT, name, "[code, name]", "[code]", name + "-wrong.csv", true, "");
            runs.add(run(name, AT));                                   // wrong credential
        });

        assertThat(runs).extracting(ExportRunView::status).containsExactly(ExportRunStatus.COMPLETED,
            ExportRunStatus.FAILED, ExportRunStatus.FAILED);
        assertThat(runs).extracting(ExportRunView::failureCode).containsExactly(null,
            ExportFailureCode.COLLISION, ExportFailureCode.PUBLICATION);
        // The probe heard the failures it must stay silent about the secrets of.
        assertThat(logs).anyMatch(l -> l.contains("(" + name + ") failed"));
        List<String> channels = everyChannel(name);
        assertThat(channels).anyMatch(c -> c.contains(runs.getFirst().sha256()));
        for (String value : secrets().values()) {
            assertThat(channels).noneMatch(c -> c.contains(value));
            assertThat(logs).noneMatch(l -> l.contains(value));
        }
    }
}
