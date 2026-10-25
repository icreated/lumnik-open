package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * #1351, the apply-after-cursor case: a Process applied once the consumer has passed a run's mark
 * never saw that run's rows, so the run must not read as evaluated.
 */
@QuarkusTest
class RunEvaluationIT {

    private static final long TENANT = 13510L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ProcessManifestStore store;

    @Test
    void a_process_applied_after_the_run_started_does_not_make_it_evaluated() {
        apply("p-" + System.nanoTime());
        // Mark 0: whatever the process cursor is, it has passed it.
        UUID run = seedEndedRun(store.processSet(TENANT).fingerprint());
        given().headers(TestIdentity.tenantAndUser(TENANT)).when().get("/api/platform/runs/" + run)
            .then().statusCode(200).body("evaluated", equalTo(true));

        // Applied after the run started — no clock involved: the set itself changed.
        apply("q-" + System.nanoTime());
        given().headers(TestIdentity.tenantAndUser(TENANT)).when().get("/api/platform/runs/" + run)
            .then().statusCode(200).body("evaluated", nullValue());
    }

    @Test
    void re_applying_or_disabling_a_process_after_the_run_started_changes_the_set() {
        String name = "r-" + System.nanoTime();
        apply(name);
        String atStart = store.processSet(TENANT).fingerprint();
        apply(name);
        String reApplied = store.processSet(TENANT).fingerprint();
        store.setEnabled(TENANT, name, false);
        assertThat(reApplied).isNotEqualTo(atStart);
        assertThat(store.processSet(TENANT).fingerprint()).isNotEqualTo(reApplied);
    }

    private void apply(String name) {
        store.upsert(TENANT, new ProcessManifestParser().parse("""
            apiVersion: apps.lumnik.io/v1
            kind: Process
            metadata: { name: %s }
            spec:
              scope: s
              on: { event: data.row.changed, table: connector.t_x }
              when: ["a=1"]
              emit: { type: decision.x, message: m }
            """.formatted(name)), "kind: Process", null);
    }

    private UUID seedEndedRun(String processSet) {
        UUID connector = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, sync_mode, skip_policy) "
           + "VALUES ('" + connector + "', " + TENANT + ", 'eval-" + connector + "', 'csv-file', '{}'::jsonb, true, "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by, "
           + "started_at, ended_at, outbox_high_water, process_set) VALUES ('" + run + "', " + TENANT + ", '" + connector
           + "', 'Completed', 'Incremental', 'manual', NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '29 minutes', 0, '" + processSet + "')");
        return run;
    }

    private void exec(String sql) {
        Jdbc.update(adminDs, "RunEvaluationIT", sql, ps -> {});
    }
}
