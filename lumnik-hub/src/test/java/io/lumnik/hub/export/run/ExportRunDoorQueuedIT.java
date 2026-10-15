package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.runtime.RunQueueEnabledProfile;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/** #952 slice 4: with lumnik.run-queue.enabled the on-demand export doors write Queued runs and start nothing themselves. */
@QuarkusTest
@TestProfile(RunQueueEnabledProfile.class)
class ExportRunDoorQueuedIT extends ExportQueuedRunITSupport {

    private long queuedCount(String name) {
        return Jdbc.queryOne(adminDs, "queued", "SELECT count(*) FROM connector.export_run WHERE tenant_id = ? "
            + "AND export_name = ? AND status = 'Queued'", ps -> { ps.setLong(1, TENANT); ps.setString(2, name.toLowerCase(Locale.ROOT)); },
            rs -> rs.getLong(1)).orElseThrow();
    }

    private String lineage(UUID id) {
        return Jdbc.queryOne(adminDs, "lineage", "SELECT retry_of || '|' || attempt || '|' || root_run_id "
            + "FROM connector.export_run WHERE id = ?", ps -> ps.setObject(1, id), rs -> rs.getString(1)).orElseThrow();
    }

    @Test
    void the_door_queues_a_run_and_starts_nothing() throws Exception {
        String name = applyExport(unique("dq") + ".csv", false);

        var started = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));

        var run = runRow(started.runId());
        assertThat(run.status()).isEqualTo("Queued");
        assertThat(run.startedAt()).isNull();
        assertThat(run.queuedAt()).isNotNull();
        assertThat(lockIsFree(exportId(name))).as("a queued run holds no lock").isTrue();
    }

    @Test
    void a_run_the_door_queued_is_started_by_the_dispatcher() throws Exception {
        String target = unique("de") + ".csv";
        String name = applyExport(target, false);
        var started = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));

        dispatcher.poll();

        assertThat(settled(started.runId()).status()).isEqualTo("Completed");
        assertThat(remote(target)).isNotEmpty();
    }

    /** The sha the door stores is the one the dispatcher recomputes — and a re-apply in between is caught. */
    @Test
    void a_manifest_re_applied_while_the_run_waited_ends_it_failed_definition() throws Exception {
        String name = applyExport(unique("dr") + ".csv", false);
        var started = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));
        reapply(name, unique("dr2") + ".csv", false);

        dispatcher.poll();

        var run = settled(started.runId());
        assertThat(run.status()).isEqualTo("Failed");
        assertThat(run.failureCode()).isEqualTo("definition");
    }

    @Test
    void a_second_start_is_already_running_and_names_the_queued_run() throws Exception {
        String name = applyExport(unique("d2") + ".csv", false);
        var first = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));

        var second = runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));

        assertThat(second).isEqualTo(new StartResult.AlreadyRunning(first.runId()));
        assertThat(queuedCount(name)).isEqualTo(1);
    }

    @Test
    void eight_simultaneous_starts_leave_exactly_one_queued_run() throws Exception {
        String name = applyExport(unique("d8") + ".csv", false);
        ExportManifestStore.Row row = row(name);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<StartResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return runner.start(ExportRunRequest.manual(TENANT, name, 7L), row);
                }));
            }
            go.countDown();
            List<StartResult> results = new ArrayList<>();
            for (Future<StartResult> f : futures) results.add(f.get(60, TimeUnit.SECONDS));   // a throw fails the test

            assertThat(results.stream().filter(StartResult.Started.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(StartResult.AlreadyRunning.class::isInstance).count()).isEqualTo(7);
            assertThat(queuedCount(name)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A due scheduled slot that meets a queued manual run is refused, not thrown, and holds no lock. */
    @Test
    void a_scheduled_launch_meeting_a_queued_run_is_already_running() throws Exception {
        String name = applyExport(unique("ds") + ".csv", false);
        runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));

        var launch = runner.launch(new ExportRunRequest(TENANT, name, ExportTrigger.SCHEDULE, null,
            Instant.parse("2026-10-12T04:00:00Z"), Instant.parse("2026-10-12T04:00:00Z"), 1, null));

        assertThat(launch.result()).isInstanceOf(StartResult.AlreadyRunning.class);
        assertThat(lockIsFree(exportId(name))).isTrue();
        assertThat(queuedCount(name)).isEqualTo(1);
    }

    /** A failed run is retried through the queue with its lineage intact. */
    @Test
    void a_retry_is_queued_with_its_lineage() throws Exception {
        String name = applyExport(unique("dt") + ".csv", true);   // wrong credential: the run fails
        var failedStart = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, name, 7L), row(name));
        dispatcher.poll();
        assertThat(settled(failedStart.runId()).status()).isEqualTo("Failed");

        var seed = TenantScope.get(TENANT, () -> QuarkusTransaction.requiringNew().call(
            () -> ledger.retrySeed(TENANT, name, failedStart.runId()).orElseThrow()));
        var retried = (StartResult.Started) runner.start(new ExportRunRequest(TENANT, name, seed.trigger(), 7L,
            seed.occurrence(), seed.artifactTime(), seed.nextAttempt(), failedStart.runId()), row(name));

        assertThat(runRow(retried.runId()).status()).isEqualTo("Queued");
        assertThat(lineage(retried.runId())).isEqualTo(failedStart.runId() + "|2|" + failedStart.runId());
    }

    @Test
    void the_http_door_answers_202_and_a_wake_up_starts_the_run_without_a_poll() throws Exception {
        String name = applyExport(unique("dh") + ".csv", false);

        String id = given().headers(TestIdentity.tenantAndUser(TENANT))
            .when().post("/api/exports/" + name + "/runs")
            .then().statusCode(202).body("runId", notNullValue())
            .extract().path("runId");

        assertThat(settled(UUID.fromString(id)).status()).isEqualTo("Completed");   // the scheduler is paused: only the wake-up started it
    }
}
