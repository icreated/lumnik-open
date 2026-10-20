package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.process.ProcessManifestParser;
import io.lumnik.hub.process.ProcessManifestStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.hub.view.SavedViewStore;
import io.lumnik.hub.workflow.WorkflowManifestParser;
import io.lumnik.hub.workflow.WorkflowManifestStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1232, #1234: removing a view, a workflow or a process, turning a workflow or a process off or on,
 * and applying a process leave a row in the audit trail with the actor, the name and the scope —
 * and a refused or unknown target leaves none. Under {@link NoBypassOidcProfile} because the
 * refusal needs a caller who is actually bound.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ManifestWriteAuditIT {

    private static final String VIEW_YAML = "apiVersion: apps.lumnik.io/v1\nkind: View\n";

    @Inject SavedViewStore views;
    @Inject WorkflowManifestStore workflows;
    @Inject ProcessManifestStore processes;
    @Inject SchemaCardStore cards;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    // lm_admin too: the process doors are admin doors.
    private static RequestSpecification unscoped() {
        return given().header("Authorization", "Bearer "
            + NoBypassOidcProfile.token(Set.of("lm_integrator", "lm_admin"), null));
    }

    private static RequestSpecification boundToMagasin() {
        return given().header("Authorization", "Bearer "
            + NoBypassOidcProfile.token(Set.of("lm_integrator", "lm_admin", "scope:magasin"), null));
    }

    @Test
    void aViewDeleteIsRecordedAndARefusedOrUnknownOneIsNot() {
        String name = "audit-view-" + System.nanoTime();
        views.upsert(name, "clients", "Clients", VIEW_YAML, null);

        boundToMagasin().delete("/api/views/" + name).then().statusCode(403);
        unscoped().delete("/api/views/" + name + "-absent").then().statusCode(404);
        assertThat(rows("SavedView", name, "DELETE")).as("a refusal writes nothing").isEmpty();
        assertThat(rows("SavedView", name + "-absent", "DELETE")).isEmpty();

        // Typed in capitals: the row is keyed by the stored name, as the apply's row is.
        unscoped().delete("/api/views/" + name.toUpperCase(Locale.ROOT)).then().statusCode(204);

        assertThat(rows("SavedView", name, "DELETE")).containsExactly("1 {\"scope\": \"clients\"}");
    }

    @Test
    void aWorkflowDeleteIsRecordedAndARefusedOrUnknownOneIsNot() {
        String name = workflowInClients();

        boundToMagasin().delete("/api/workflows/" + name).then().statusCode(403);
        unscoped().delete("/api/workflows/" + name + "-absent").then().statusCode(404);
        assertThat(rows("WorkflowManifest", name, "DELETE")).as("a refusal writes nothing").isEmpty();
        assertThat(rows("WorkflowManifest", name + "-absent", "DELETE")).isEmpty();

        unscoped().delete("/api/workflows/" + name).then().statusCode(204);

        assertThat(rows("WorkflowManifest", name, "DELETE")).containsExactly("1 {\"scope\": \"clients\"}");
    }

    @Test
    void turningAWorkflowOffAndOnIsRecordedAndARefusedOrUnknownToggleIsNot() {
        String name = workflowInClients();

        toggle(boundToMagasin(), "workflows", name, false).statusCode(403);
        toggle(unscoped(), "workflows", name + "-absent", false).statusCode(404);
        assertThat(rows("WorkflowManifest", name, "UPDATE")).as("a refusal writes nothing").isEmpty();
        assertThat(rows("WorkflowManifest", name + "-absent", "UPDATE")).isEmpty();

        toggle(unscoped(), "workflows", name, false).statusCode(200);
        toggle(unscoped(), "workflows", name, true).statusCode(200);

        assertThat(rows("WorkflowManifest", name, "UPDATE")).containsExactly(
            "1 {\"scope\": \"clients\", \"enabled\": false}",
            "1 {\"scope\": \"clients\", \"enabled\": true}");
    }

    @Test
    void aProcessApplyIsRecordedAndARefusedOrUnknownOneIsNot() {
        processCardAndTable();
        String name = "audit-proc-" + System.nanoTime();
        String yaml = processManifest(name, PROCESS_SCOPE);

        applyProcess(boundToMagasin(), yaml).statusCode(403);
        applyProcess(unscoped(), processManifest(name, PROCESS_SCOPE + "-nocard")).statusCode(404);
        assertThat(rows("Process", name, "APPLY")).as("a refusal writes nothing").isEmpty();

        applyProcess(unscoped(), yaml).statusCode(200);

        assertThat(rows("Process", name, "APPLY")).containsExactly("1 {\"scope\": \"" + PROCESS_SCOPE
            + "\", \"table\": \"" + PROCESS_TABLE + "\", \"manifest_sha256\": \"" + AuditRecorder.sha256(yaml) + "\"}");
    }

    @Test
    void aProcessDeleteIsRecordedAndARefusedOrUnknownOneIsNot() {
        String name = processInScope();

        boundToMagasin().delete("/api/processes/" + name).then().statusCode(403);
        unscoped().delete("/api/processes/" + name + "-absent").then().statusCode(404);
        assertThat(rows("Process", name, "DELETE")).as("a refusal writes nothing").isEmpty();
        assertThat(rows("Process", name + "-absent", "DELETE")).isEmpty();

        unscoped().delete("/api/processes/" + name).then().statusCode(204);

        assertThat(rows("Process", name, "DELETE")).containsExactly("1 {\"scope\": \"" + PROCESS_SCOPE + "\"}");
    }

    @Test
    void turningAProcessOffAndOnIsRecordedAndARefusedOrUnknownToggleIsNot() {
        String name = processInScope();

        toggle(boundToMagasin(), "processes", name, false).statusCode(403);
        toggle(unscoped(), "processes", name + "-absent", false).statusCode(404);
        assertThat(rows("Process", name, "UPDATE")).as("a refusal writes nothing").isEmpty();
        assertThat(rows("Process", name + "-absent", "UPDATE")).isEmpty();

        toggle(unscoped(), "processes", name, false).statusCode(200);
        toggle(unscoped(), "processes", name, true).statusCode(200);

        assertThat(rows("Process", name, "UPDATE")).containsExactly(
            "1 {\"scope\": \"" + PROCESS_SCOPE + "\", \"enabled\": false}",
            "1 {\"scope\": \"" + PROCESS_SCOPE + "\", \"enabled\": true}");
    }

    /** {@code PUT /api/<kind>/<name>/enabled}, kind being {@code workflows} or {@code processes}. */
    private static ValidatableResponse toggle(RequestSpecification caller, String kind, String name, boolean enabled) {
        return caller.contentType("application/json").body("{\"enabled\": " + enabled + "}")
            .put("/api/" + kind + "/" + name + "/enabled").then();
    }

    private static final String PROCESS_SCOPE = "procaudit";
    private static final String PROCESS_TABLE = "connector.t_procaudit";

    /** What the process apply door asks for: the trigger table in the scope's card, filled by a row writer. */
    private void processCardAndTable() {
        Jdbc.update(adminDs, "ManifestWriteAuditIT.table",
            "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                + "VALUES (1, 'procaudit', 'connector', 't_procaudit', '{}'::jsonb) ON CONFLICT DO NOTHING",
            ps -> {});
        cards.save(1L, "registry", new SchemaCard(PROCESS_SCOPE, List.of(new SchemaCard.Table(PROCESS_TABLE,
            "Audit", List.of(new SchemaCard.Column("code", "text", "Code"))))));
    }

    private static String processManifest(String name, String scope) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Process
            metadata: { name: %s }
            spec:
              scope: %s
              on: { event: data.row.changed, table: %s }
              when: [ code=A ]
              emit: { type: decision.%s, message: "A" }
            """.formatted(name, scope, PROCESS_TABLE, name);
    }

    private static ValidatableResponse applyProcess(RequestSpecification caller, String yaml) {
        return caller.config(YamlBody.CONFIG).contentType("application/yaml").body(yaml)
            .post("/api/processes/apply").then();
    }

    /** A process in {@link #PROCESS_SCOPE}, written straight to the store: these doors answer before
     *  reading the card or the table. */
    private String processInScope() {
        String name = "audit-proc-" + System.nanoTime();
        String yaml = processManifest(name, PROCESS_SCOPE);
        processes.upsert(1, new ProcessManifestParser().parse(yaml), yaml, null);
        return name;
    }

    /** A workflow in {@code clients}, written straight to the store: the apply door's own card and
     *  table checks belong to its tests, and the doors here answer before reading any of that. */
    private String workflowInClients() {
        String name = "audit-wf-" + System.nanoTime();
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: %s }
            spec:
              scope: clients
              provenance: declared
              on: { table: connector.t_%s, column: statut }
              states:
                - { code: A, initial: true }
                - { code: B, terminal: true }
              transitions:
                - { from: [A], to: B }
            """.formatted(name, name.replace('-', '_'));
        workflows.upsert(1, new WorkflowManifestParser().parse(yaml), yaml, null);
        return name;
    }

    /** Each row's actor and changes, as {@code "<actor_id> <changes>"}, oldest first. */
    private List<String> rows(String type, String key, String operation) {
        return Jdbc.query(adminDs, "ManifestWriteAuditIT.rows",
            "SELECT actor_id || ' ' || changes::text FROM core.audit_outbox "
                + "WHERE tenant_id = 1 AND entity_type = ? AND entity_key = ? AND operation = ? ORDER BY id",
            ps -> {
                ps.setString(1, type);
                ps.setString(2, key);
                ps.setString(3, operation);
            },
            rs -> rs.getString(1));
    }
}
