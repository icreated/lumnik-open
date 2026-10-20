package io.lumnik.hub.workflow;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The losing side of a concurrent apply hits the DB's {@code uq_workflow_manifest_tenant_column}
 * constraint (V32) inside {@code store.upsert}, wrapped as a bare {@code RuntimeException} — the
 * store's own convention. That must degrade to the same 400 surface as the explicit
 * {@code findOtherOnColumn} pre-check, not leak as a 500: a user-correctable conflict is not an
 * internal error. {@code WorkflowManifestStore} is mocked here specifically to force the DB-race
 * outcome deterministically — the pre-check passes (no other row, as the loser's own transaction
 * would have seen), then upsert throws the wrapped unique-violation the real constraint raises.
 */
@QuarkusTest
class WorkflowApplyConflictIT {

    private static final String TENANT = "7305";

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-conflict-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_conflict_it, column: statut }
          states:
            - { code: A, initial: true }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    @InjectMock WorkflowManifestStore store;

    @Inject SchemaCardStore cards;

    @BeforeEach
    void seed() {
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("demo", List.of()));
        Mockito.when(store.findOtherOnColumn(Mockito.anyLong(), Mockito.anyString(),
            Mockito.anyString(), Mockito.anyString())).thenReturn(Optional.empty());
    }

    @Test
    void a_dbLevel_unique_violation_degrades_to_400_not_500() {
        SQLException unique = new SQLException("duplicate key value violates unique constraint "
            + "\"uq_workflow_manifest_tenant_column\"", "23505");
        Mockito.doThrow(new RuntimeException("WorkflowManifestStore.upsert: " + unique.getMessage(), unique))
            .when(store).upsert(Mockito.anyLong(), Mockito.any(), Mockito.anyString(), Mockito.any());

        given().config(YamlBody.CONFIG).header("X-Tenant-ID", TENANT).header("X-User-ID", "1")
            .contentType("application/yaml").body(YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(400)
            .body("error", containsString("already has a declared lifecycle"))
            .body("error", containsString("a column has exactly one lifecycle"))
            .body("error", containsString("re-apply"));
    }

    @Test
    void any_other_store_failure_is_a_500_that_does_not_quote_its_message() {
        SQLException outage = new SQLException("permission denied for table t_secret_payroll", "42501");
        Mockito.doThrow(new RuntimeException("WorkflowManifestStore.upsert: " + outage.getMessage(), outage))
            .when(store).upsert(Mockito.anyLong(), Mockito.any(), Mockito.anyString(), Mockito.any());

        given().config(YamlBody.CONFIG).header("X-Tenant-ID", TENANT).header("X-User-ID", "1")
            .contentType("application/yaml").body(YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(500)
            .body("error", equalTo("workflow apply failed"));
    }
}
