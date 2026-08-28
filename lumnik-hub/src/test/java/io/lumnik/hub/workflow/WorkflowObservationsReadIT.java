package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** The tape's REST read: flattened, stillest first, tenant from the request context. */
@QuarkusTest
class WorkflowObservationsReadIT {

    @Inject WorkflowObservationStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void seed() throws Exception {
        try (var c = adminDs.getConnection(); var st = c.createStatement()) {
            st.execute("DELETE FROM core.workflow_observation WHERE doc_key LIKE 'OBSREAD-%'");
        }
        Instant now = Instant.now();
        store.insertNew(1L, "wf-read", "OBSREAD-OLD", "draft", now.minusSeconds(3600));
        store.insertNew(1L, "wf-read", "OBSREAD-NEW", "sent",  now.minusSeconds(60));
        store.insertNew(2L, "wf-read", "OBSREAD-T2",  "draft", now.minusSeconds(7200));
    }

    @Test
    void observations_are_flattened_stillest_first_and_tenant_bound() {
        given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .when().get("/api/platform/workflows/observations")
            .then().statusCode(200)
            .body("findAll { it.docKey.startsWith('OBSREAD-') }", hasSize(2))
            .body("findAll { it.docKey.startsWith('OBSREAD-') }[0].docKey", equalTo("OBSREAD-OLD"))
            .body("findAll { it.docKey.startsWith('OBSREAD-') }[0].workflow", equalTo("wf-read"))
            .body("findAll { it.docKey.startsWith('OBSREAD-') }[0].state", equalTo("draft"));
    }

    @Test
    void limit_is_honored() {
        given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
            .when().get("/api/platform/workflows/observations?limit=1")
            .then().statusCode(200)
            .body("size()", equalTo(1));
    }
}
