package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowApplyIT {

    private static final String TENANT = "7301";

    // RestAssured has no built-in encoder for "application/yaml" — without this, a String body
    // sent with that content-type is mangled. Copied verbatim from ProcessApplyResourceIT /
    // EntityApplyIT, the existing green apply ITs.
    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-apply-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_apply_it, column: statut }
          states:
            - { code: A, initial: true }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    @Inject SchemaCardStore cards;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /** Prior manifests are deleted before this class's ordered sequence runs, unconditionally —
     *  no flag to gate it. The old guard was a static one-time {@code cleaned} boolean, gated
     *  behind {@code if (!cleaned)}: correct only because a fresh JVM always starts it false, but
     *  a mutable static survives class reuse within a JVM (e.g. Surefire fork reuse), so a second
     *  run in the same fork would silently skip the delete and leave stale rows — making the
     *  "one lifecycle per column" assertions below pass without testing anything. {@code @BeforeAll}
     *  (with {@code PER_CLASS} instance lifecycle, so it can use the injected DataSource) needs no
     *  such flag: JUnit guarantees it runs exactly once per class execution regardless of any
     *  residual state. The delete is cheap and scoped to one tenant. */
    @BeforeAll
    void cleanPriorManifests() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM core.workflow_manifest WHERE tenant_id = " + TENANT);
        }
    }

    /** The scope must exist as a discovered SchemaCard for apply to accept it (a typo'd scope
     *  would otherwise index the lifecycle where no scope-bound user can ever retrieve it).
     *  The trigger table is deliberately NOT in the card: declaring before ingesting is legal. */
    @BeforeEach
    void seedScope() {
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("demo", List.of()));
    }

    // Auth incantation copied from ProcessApplyResourceIT / EntityApplyIT (the existing green
    // apply ITs): dev-bypass mode (%test.lumnik.security.dev-bypass=true) reads tenant/user from
    // X-Tenant-ID / X-User-ID headers (TenantContextFilter) and grants ALL roles — including
    // lm_integrator — automatically (RequiresPermission javadoc). No @TestProfile needed.
    private static RequestSpecification asIntegrator() {
        return given().config(YamlBody.CONFIG).header("X-Tenant-ID", TENANT).header("X-User-ID", "1");
    }

    @Test
    @Order(1)
    void apply_upserts_and_reports_data_truth() {
        asIntegrator()
            .contentType("application/yaml").body(YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(200)
            .body("name", equalTo("wf-apply-it"))
            .body("scope", equalTo("demo"))            // the tag actually written
            .body("dataTruth.tableMissing", equalTo(true)); // table not ingested yet — reported, not blocked
    }

    @Test
    @Order(2)
    void unknown_key_is_refused_with_the_honored_surface() {
        asIntegrator()
            .contentType("application/yaml").body(YAML.replace("scope: demo", "scope: demo\n  scop: x"))
            .when().post("/api/workflows/apply")
            .then().statusCode(400)
            .body("error", containsString("spec.scop"))
            .body("error", containsString("honors"));
    }

    @Test
    @Order(3)
    void list_and_manifest_roundtrip() {
        asIntegrator()
            .when().get("/api/workflows")
            .then().statusCode(200)
            .body("find { it.name == 'wf-apply-it' }.table", equalTo("connector.t_wf_apply_it"));
        asIntegrator()
            .when().get("/api/workflows/wf-apply-it/manifest")
            .then().statusCode(200).contentType(containsString("yaml"))
            .body(containsString("wf-apply-it"));
        asIntegrator()
            .when().get("/api/workflows/nope/manifest")
            .then().statusCode(404);
    }

    /** A typo'd scope used to be accepted: 200, clean report, chunk tagged with a scope nobody
     *  holds — the lifecycle invisible forever, and nothing said. */
    @Test
    @Order(4)
    void unknown_scope_is_refused() {
        asIntegrator()
            .contentType("application/yaml")
            .body(YAML.replace("scope: demo", "scope: demoo").replace("wf-apply-it", "wf-typo-scope"))
            .when().post("/api/workflows/apply")
            .then().statusCode(404)   // same condition, same code as kind:Process's apply door
            .body("error", containsString("no schema for scope 'demoo'"))
            .body("error", containsString("discovery"));
    }

    /** A column has exactly one lifecycle: a rename must not leave two contradictory declared
     *  graphs over the same column, both retrieved, the chat forced to choose. */
    @Test
    @Order(5)
    void a_second_workflow_on_the_same_column_is_refused() {
        asIntegrator()
            .contentType("application/yaml")
            .body(YAML.replace("name: wf-apply-it", "name: wf-apply-it-renamed"))
            .when().post("/api/workflows/apply")
            .then().statusCode(400)
            .body("error", containsString("wf-apply-it"))
            .body("error", containsString("connector.t_wf_apply_it"))
            .body("error", containsString("re-apply"));
    }

    /** ...and the update path stays open: same name, same column, 200, one row. */
    @Test
    @Order(6)
    void re_applying_the_same_name_still_updates() {
        asIntegrator()
            .contentType("application/yaml")
            .body(YAML.replace("{ code: A, initial: true }", "{ code: A, initial: true, label: Ay }"))
            .when().post("/api/workflows/apply")
            .then().statusCode(200)
            .body("name", equalTo("wf-apply-it"));
        asIntegrator()
            .when().get("/api/workflows")
            .then().statusCode(200)
            .body("findAll { it.name == 'wf-apply-it' }", hasSize(1));
        asIntegrator()
            .when().get("/api/workflows/wf-apply-it/manifest")
            .then().statusCode(200).body(containsString("label: Ay"));
    }

    @Inject
    @DataSource("admin")
    AgroalDataSource auditAdminDs;

    @Test
    @Order(90)
    void a_successful_apply_lands_in_the_audit_outbox() throws Exception {
        // Audit P4: workflow applies were recorded nowhere. The full chain is proven here:
        // HTTP apply -> core.audit_outbox row with actor, key and manifest hash.
        try (var c = auditAdminDs.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery(
                 "SELECT count(*) FROM core.audit_outbox "
               + "WHERE entity_type = 'WorkflowManifest' AND operation = 'APPLY' "
               + "AND entity_key = 'wf-apply-it' AND actor_id = 1")) {
            rs.next();
            Assertions.assertTrue(rs.getLong(1) >= 1,
                "a green workflow apply must land in core.audit_outbox");
        }
    }

    private static final String OTHER_TABLE = "connector.t_wf_other_metier";
    private static final String CROSS_SCOPE_YAML = YAML
        .replace("name: wf-apply-it", "name: wf-cross-scope-it")
        .replace("connector.t_wf_apply_it", OTHER_TABLE);

    /**
     * The scope check answers "may you declare INTO this scope"; it says nothing about which table
     * you then point at. Without this refusal a scope-bound integrator could name another métier's
     * ingest table and read its values back out of the data-truth report — up to LIMIT distinct
     * values of any column, with row counts, on a 200. Intra-tenant, and refused everywhere else:
     * `kind:Process` requires its trigger table to be in the card, `kind:View` resolves columns
     * against it, and a workflow required nothing.
     */
    @Test
    @Order(10)
    void a_table_belonging_to_another_metier_is_refused() {
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("rh", List.of(
            new SchemaCard.Table(OTHER_TABLE, "Paie", List.of(
                new SchemaCard.Column("salaire_net", "text", "Salaire net"))))));

        asIntegrator()
            .contentType("application/yaml").body(CROSS_SCOPE_YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(400)
            .body("error", containsString(OTHER_TABLE))
            .body("error", containsString("another métier"));
    }

    /**
     * …and the two legal cases stay legal, which is the whole reason this is not simply
     * "the table must be in my card" the way `kind:Process` has it. A table in NO card is the
     * declare-before-ingest case — pinned by {@code apply_upserts_and_reports_data_truth} above,
     * which asserts {@code tableMissing} on a 200. A table in MY card is the ordinary case, and it
     * must not be caught by the other-scope query when several cards advertise the same table.
     */
    @Test
    @Order(11)
    void the_same_table_in_my_own_card_is_accepted_even_when_another_scope_has_it_too() {
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("rh", List.of(
            new SchemaCard.Table(OTHER_TABLE, "Paie", List.of(
                new SchemaCard.Column("salaire_net", "text", "Salaire net"))))));
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("demo", List.of(
            new SchemaCard.Table(OTHER_TABLE, "Commandes", List.of(
                new SchemaCard.Column("statut", "text", "Statut"))))));

        asIntegrator()
            .contentType("application/yaml").body(CROSS_SCOPE_YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(200);
    }
}
