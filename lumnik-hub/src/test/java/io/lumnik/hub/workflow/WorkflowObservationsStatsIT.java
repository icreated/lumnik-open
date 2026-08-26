package io.lumnik.hub.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The sponsor's number, computed by the hub (chantier C1): one aggregate over
 * core.workflow_observation — dormant count and median detection delay per workflow.
 * One PK-prefix range scan (PK = tenant_id, workflow_name, doc_key), one round-trip,
 * whatever the volume. The store runs on the admin datasource: the explicit tenant
 * filter IS the isolation guard, asserted here.
 */
@QuarkusTest
class WorkflowObservationsStatsIT {

    private static final long TENANT = 5001L;
    private static final long OTHER_TENANT = 5002L;

    @Inject WorkflowObservationStore store;

    private void seed() {
        Instant now = Instant.now();
        // wf-a: 3 watched, 2 dormant with detection delays of 1 day and 3 days → median 2 days.
        store.insertNew(TENANT, "wf-stats-a", "DOC-1", "EN LITIGE", now.minus(9, ChronoUnit.DAYS));
        store.markAlerted(TENANT, "wf-stats-a", "DOC-1", now.minus(8, ChronoUnit.DAYS));
        store.insertNew(TENANT, "wf-stats-a", "DOC-2", "EN LITIGE", now.minus(5, ChronoUnit.DAYS));
        store.markAlerted(TENANT, "wf-stats-a", "DOC-2", now.minus(2, ChronoUnit.DAYS));
        store.insertNew(TENANT, "wf-stats-a", "DOC-3", "EN_COURS", now.minus(1, ChronoUnit.DAYS));
        // wf-b: watched but nothing dormant — the median must be ABSENT, never a confident zero.
        store.insertNew(TENANT, "wf-stats-b", "DOC-9", "EN_COURS", now.minus(1, ChronoUnit.DAYS));
        // Another tenant's dormant row must not leak into the aggregate.
        store.insertNew(OTHER_TENANT, "wf-stats-x", "DOC-X", "EN LITIGE", now.minus(30, ChronoUnit.DAYS));
        store.markAlerted(OTHER_TENANT, "wf-stats-x", "DOC-X", now.minus(1, ChronoUnit.DAYS));
    }

    @Test
    void stats_aggregate_dormants_and_median_detection_per_workflow() {
        seed();
        given()
            .header("X-Tenant-ID", String.valueOf(TENANT)).header("X-User-ID", "1")
        .when()
            .get("/api/platform/workflows/observations/stats")
        .then()
            .statusCode(200)
            .body("find { it.workflow == 'wf-stats-a' }.watched", is(3))
            .body("find { it.workflow == 'wf-stats-a' }.dormant", is(2))
            .body("find { it.workflow == 'wf-stats-a' }.medianDetectionSeconds",
                  is(2 * 24 * 3600))
            .body("find { it.workflow == 'wf-stats-b' }.watched", is(1))
            .body("find { it.workflow == 'wf-stats-b' }.dormant", is(0))
            .body("find { it.workflow == 'wf-stats-b' }.medianDetectionSeconds", nullValue())
            .body("find { it.workflow == 'wf-stats-x' }", nullValue());
    }
}
