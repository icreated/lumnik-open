package io.lumnik.hub.workflow;

import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
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
            st.execute("DELETE FROM core.workflow_transition WHERE doc_key LIKE 'OBSREAD-%'");
        }
        Instant now = Instant.now();
        store.insertNew(1L, "wf-read", "OBSREAD-OLD", "draft", now.minusSeconds(3600));
        store.insertNew(1L, "wf-read", "OBSREAD-NEW", "sent",  now.minusSeconds(60));
        store.insertNew(2L, "wf-read", "OBSREAD-T2",  "draft", now.minusSeconds(7200));
        store.insertNew(1L, "wf-other", "OBSREAD-OTHER", "draft", now.minusSeconds(9000));
        store.insertNew(1L, "wf-read", "OBSREAD-MOVED", "draft", now.minusSeconds(3000));
        store.recordTransition(1L, "wf-read", "OBSREAD-MOVED", "draft", "sent", now.minusSeconds(1800));
        store.recordTransition(1L, "wf-read", "OBSREAD-MOVED", "sent", "done", now.minusSeconds(600));
        store.recordTransition(2L, "wf-read", "OBSREAD-T2", "draft", "sent", now.minusSeconds(300));
    }

    /** #1368: the journal, newest first; each move says when its document entered the state it left. */
    @Test
    void transitions_read_the_journal_with_when_each_from_state_was_entered() {
        var rows = given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations/transitions?workflow=wf-read&doc=OBSREAD-MOVED")
            .then().statusCode(200)
            .body("size()", equalTo(2))
            .body("[0].from", equalTo("sent")).body("[0].to", equalTo("done"))
            .body("[1].from", equalTo("draft")).body("[1].to", equalTo("sent"))
            .extract().jsonPath();
        // the second move's from-state was entered by the first move…
        assertThat(rows.getString("[0].enteredFromAt"))
            .isEqualTo(rows.getString("[1].observedAt"));
        // …and the first one's when the document was first observed (a lower bound)
        assertThat(Instant.parse(rows.getString("[1].enteredFromAt")))
            .isBefore(Instant.parse(rows.getString("[1].observedAt")));
    }

    /** Copilot on #1370: a move without its ledger row is left out, never served with a null entry time. */
    @Test
    void a_move_without_its_ledger_row_is_left_out() throws Exception {
        try (var c = adminDs.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO core.workflow_transition (tenant_id, workflow_name, doc_key, from_state, to_state, observed_at) "
                + "VALUES (1, 'wf-read', 'OBSREAD-ORPHAN', 'draft', 'sent', NOW())");
        }
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations/transitions?workflow=wf-read")
            .then().statusCode(200)
            .body("findAll { it.docKey == 'OBSREAD-ORPHAN' }", hasSize(0))
            .body("findAll { it.enteredFromAt == null }", hasSize(0));
    }

    @Test
    void transitions_are_tenant_bound() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations/transitions?workflow=wf-read")
            .then().statusCode(200)
            .body("findAll { it.docKey == 'OBSREAD-T2' }", hasSize(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc=", "doc=a&doc=b", "workflow=a&workflow=b"})
    void a_blank_or_repeated_transitions_filter_is_400(String query) {
        given().urlEncodingEnabled(false).headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations/transitions?" + query)
            .then().statusCode(400);
    }

    /** #1361: the filter narrows the query, not the page — limit=1 still finds wf-read's stillest. */
    @Test
    void workflow_narrows_the_query_not_the_page() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations?workflow=wf-read&limit=1")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].docKey", equalTo("OBSREAD-OLD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow=", "workflow=%20", "workflow=a&workflow=b"})
    void a_blank_or_repeated_workflow_is_400(String query) {
        given().urlEncodingEnabled(false).headers(TestIdentity.tenantAndUser(1))   // %20 reaches the hub as a space
            .when().get("/api/platform/workflows/observations?" + query)
            .then().statusCode(400);
    }

    @Test
    void observations_are_flattened_stillest_first_and_tenant_bound() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations")
            .then().statusCode(200)
            .body("findAll { it.docKey.startsWith('OBSREAD-') && it.workflow == 'wf-read' }", hasSize(3))
            .body("findAll { it.docKey.startsWith('OBSREAD-') && it.workflow == 'wf-read' }[0].docKey", equalTo("OBSREAD-OLD"))
            .body("findAll { it.docKey.startsWith('OBSREAD-') && it.workflow == 'wf-read' }[0].state", equalTo("draft"));
    }

    @Test
    void limit_is_honored() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/workflows/observations?limit=1")
            .then().statusCode(200)
            .body("size()", equalTo(1));
    }
}
