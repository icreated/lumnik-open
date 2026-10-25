package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #1254: the workflow reads under {@code /api/platform/workflows} — the graphs, one graph, the
 * tape and its stats — show a bound caller only the workflows of the scopes it holds; another
 * métier's graph by name is the uniform 403. An unbound caller reads the tenant.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class WorkflowReadsScopeIT {

    private static final String SALES = "wf-scope-sales";
    private static final String STOCK = "wf-scope-stock";
    /** A tenant of its own: the unbound list parses every manifest of the tenant, so another
     *  test's leftover in tenant 1 must not decide this one. */
    private static final long TENANT = 7254L;

    @Inject WorkflowManifestStore manifests;
    @Inject WorkflowObservationStore tape;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM core.workflow_observation WHERE tenant_id = " + TENANT + " AND workflow_name LIKE 'wf-scope-%'");
            st.execute("DELETE FROM core.workflow_transition WHERE tenant_id = " + TENANT + " AND workflow_name LIKE 'wf-scope-%'");
            st.execute("DELETE FROM core.workflow_manifest WHERE tenant_id = " + TENANT + " AND name LIKE 'wf-scope-%'");
        }
        declare(SALES, "sales");
        declare(STOCK, "stock");
        Instant now = Instant.now();
        tape.insertNew(TENANT, SALES, "WFSCOPE-S1", "A", now.minusSeconds(60));
        tape.insertNew(TENANT, STOCK, "WFSCOPE-K1", "A", now.minusSeconds(3600));
        tape.recordTransition(TENANT, SALES, "WFSCOPE-S1", "A", "B", now.minusSeconds(30));
        tape.recordTransition(TENANT, STOCK, "WFSCOPE-K1", "A", "B", now.minusSeconds(30));
    }

    private void declare(String name, String scope) {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: %s }
            spec:
              scope: %s
              provenance: declared
              on: { table: connector.t_%s, column: statut }
              states:
                - { code: A, initial: true }
                - { code: B, terminal: true }
              transitions:
                - { from: [A], to: B }
            """.formatted(name, scope, name.replace('-', '_'));
        manifests.upsert(TENANT, new WorkflowManifestParser().parse(yaml), yaml, null);
    }

    private static RequestSpecification as(String... scopes) {
        Set<String> roles = new HashSet<>(Set.of("lm_admin", "lm_integrator"));
        for (String s : scopes) roles.add("scope:" + s);
        return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null, TENANT));
    }

    private static List<String> names(RequestSpecification caller, String path, String field) {
        List<String> all = caller.get(path).then().statusCode(200).extract().jsonPath().getList(field);
        return all.stream().filter(n -> n.startsWith("wf-scope-")).toList();
    }

    @Test
    void a_bound_caller_reads_only_the_graphs_of_its_scopes() {
        assertThat(names(as("sales"), "/api/platform/workflows", "name")).containsExactly(SALES);
        assertThat(names(as(), "/api/platform/workflows", "name")).containsExactlyInAnyOrder(SALES, STOCK);
    }

    @Test
    void another_metiers_broken_manifest_does_not_fail_a_bound_callers_reads() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO core.workflow_manifest (tenant_id, name, yaml_text, scope, on_table, on_column) "
                + "VALUES (" + TENANT + ", 'wf-scope-broken', 'kind: NotAWorkflow', 'stock', 'connector.t_x', 'statut')");
        }
        assertThat(names(as("sales"), "/api/platform/workflows", "name")).containsExactly(SALES);
        as("sales").get("/api/platform/workflows/wf-scope-broken").then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }

    @Test
    void another_metiers_graph_by_name_is_refused() {
        as("sales").get("/api/platform/workflows/" + STOCK).then().statusCode(403)
            .body("error", equalTo("scope not granted"));
        as("sales").get("/api/platform/workflows/" + SALES).then().statusCode(200);
        as().get("/api/platform/workflows/" + STOCK).then().statusCode(200);
    }

    /** #1361: naming another métier's workflow in the filter is the same refusal as by name. */
    @Test
    void another_metiers_workflow_as_a_tape_filter_is_refused() {
        as("sales").get("/api/platform/workflows/observations?workflow=" + STOCK).then().statusCode(403)
            .body("error", equalTo("scope not granted"));
        as("sales").get("/api/platform/workflows/observations?workflow=" + SALES).then().statusCode(200);
    }

    @Test
    void a_bound_caller_reads_only_the_tape_of_its_scopes() {
        assertThat(names(as("sales"), "/api/platform/workflows/observations?limit=1000", "workflow"))
            .containsExactly(SALES);
        assertThat(names(as(), "/api/platform/workflows/observations?limit=1000", "workflow"))
            .containsExactlyInAnyOrder(SALES, STOCK);
    }

    @Test
    void a_bound_caller_reads_only_the_stats_of_its_scopes() {
        assertThat(names(as("sales"), "/api/platform/workflows/observations/stats", "workflow"))
            .containsExactly(SALES);
        assertThat(names(as(), "/api/platform/workflows/observations/stats", "workflow"))
            .containsExactlyInAnyOrder(SALES, STOCK);
    }

    /** #1368: the journal is narrowed like the tape, and another métier's name is the same refusal. */
    @Test
    void a_bound_caller_reads_only_the_transitions_of_its_scopes() {
        assertThat(names(as("sales"), "/api/platform/workflows/observations/transitions?limit=1000", "workflow"))
            .containsExactly(SALES);
        assertThat(names(as(), "/api/platform/workflows/observations/transitions?limit=1000", "workflow"))
            .containsExactlyInAnyOrder(SALES, STOCK);
        as("sales").get("/api/platform/workflows/observations/transitions?workflow=" + STOCK).then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }
}
