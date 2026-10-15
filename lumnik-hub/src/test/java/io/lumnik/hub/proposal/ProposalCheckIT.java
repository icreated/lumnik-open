package io.lumnik.hub.proposal;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.LogProbe;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.lumnik.hub.workflow.WorkflowRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProposalCheckIT {

    private static final String OWNER = "7401";
    private static final String STRANGER = "7402";

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: %s }
        spec:
          scope: demo
          provenance: declared
          on: { table: %s, column: %s, key: %s }
          dormancy: { after: 1m }
          states:
            - { code: A, initial: true }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    private boolean workflowsApplied;

    @Inject SchemaCardStore cards;
    @InjectSpy WorkflowRegistry registry;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static RequestSpecification as(String tenant) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-ID", tenant).header("X-User-ID", "1");
    }

    @BeforeAll
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            // one lifecycle per (table, column): the workflow with a missing key needs a table of its own
            for (String table : List.of("t_prop_check_it", "t_prop_check_it_nokey")) {
                s.execute("CREATE TABLE IF NOT EXISTS connector." + table
                    + " (id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, statut TEXT, num TEXT)");
            }
            s.execute("DELETE FROM core.workflow_manifest WHERE tenant_id IN (" + OWNER + ", " + STRANGER + ")");
        }
    }

    /**
     * Not in {@code @BeforeAll}: the Quarkus extension points RestAssured at the test port only when a
     * test starts, so an HTTP call made earlier goes to RestAssured's default, localhost:8080 — on a
     * developer machine, whatever else listens there. Once per class is enough (the apply door is an
     * idempotent upsert and nothing here deletes these four rows), and the first test already runs at the
     * test port.
     */
    @BeforeEach
    void applyTheWorkflows() {
        if (workflowsApplied) return;
        cards.save(Long.parseLong(OWNER), "registry", new SchemaCard("demo", List.of()));
        apply("wf-pc-good", "connector.t_prop_check_it", "statut", "num");
        apply("wf-pc-nocolumn", "connector.t_prop_check_it", "no_such_col", "num");
        apply("wf-pc-nokey", "connector.t_prop_check_it_nokey", "statut", "no_such_key");
        apply("wf-pc-notable", "connector.t_prop_check_it_absent", "statut", "num");
        workflowsApplied = true;
    }

    static String workflowYaml(String name, String table, String column, String key) {
        return YAML.formatted(name, table, column, key);
    }

    private void apply(String name, String table, String column, String key) {
        as(OWNER).contentType("application/yaml").body(workflowYaml(name, table, column, key))
            .when().post("/api/workflows/apply").then().log().ifValidationFails().statusCode(200);
    }

    private static String dossier() {
        return DossierReaderTest.fixture("valid/minimal.md");
    }

    private String post(String tenant, String workflow, String body) {
        RequestSpecification r = as(tenant).contentType("text/plain").body(body.getBytes(StandardCharsets.UTF_8));
        if (workflow != null) r = r.queryParam("workflow", workflow);
        return r.when().post("/api/proposal/check")
            .then().statusCode(200).contentType("text/markdown;charset=UTF-8").extract().asString();
    }

    private Dossier check(String tenant, String workflow) {
        return DossierReader.read(post(tenant, workflow, dossier()));
    }

    private static String asked(String checkedDossier) {
        return DossierReader.read(checkedDossier).answers().get(1).asked();
    }

    private static String c(Dossier d, String id) {
        return d.leads().get(0).conditions().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().check();
    }

    @Test
    void a_good_workflow_confirms_what_it_can_and_nothing_else() {
        Dossier d = check(OWNER, "wf-pc-good");

        assertThat(c(d, "C1")).isEqualTo("confirmed");
        assertThat(c(d, "C3")).isEqualTo("confirmed");
        assertThat(c(d, "C5")).isEqualTo("confirmed");
        assertThat(c(d, "C2")).isEqualTo("unverified");
        assertThat(c(d, "C4")).isEqualTo("missing");
    }

    @Test
    void a_missing_status_column_is_refuted_and_leaves_the_key_alone() {
        Dossier d = check(OWNER, "wf-pc-nocolumn");

        assertThat(c(d, "C1")).isEqualTo("refuted");
        assertThat(c(d, "C3")).isEqualTo("missing");     // the probe stopped at 42703: no key verdict
    }

    @Test
    void a_missing_key_column_is_refuted() {
        Dossier d = check(OWNER, "wf-pc-nokey");

        assertThat(c(d, "C1")).isEqualTo("confirmed");
        assertThat(c(d, "C3")).isEqualTo("refuted");
    }

    @Test
    void an_absent_table_concludes_nothing_about_columns_but_the_door_still_confirms() {
        Dossier d = check(OWNER, "wf-pc-notable");

        assertThat(c(d, "C1")).isEqualTo("unverified");
        assertThat(c(d, "C3")).isEqualTo("missing");
        assertThat(c(d, "C5")).isEqualTo("confirmed");
    }

    @Test
    void another_tenants_workflow_and_an_unknown_name_are_indistinguishable_from_no_designation() {
        String undesignated = post(OWNER, null, dossier());
        String stranger = post(STRANGER, "wf-pc-good", dossier());
        String unknown = post(OWNER, "no-such-workflow", dossier());

        assertThat(stranger).isEqualTo(undesignated);
        assertThat(unknown).isEqualTo(undesignated);
    }

    /**
     * The garbled row must not outlive its test: the dormancy sweep re-parses EVERY stored manifest of
     * every tenant, so a row nobody can parse fails the sweep's own ITs in the same database.
     */
    @AfterEach
    void removeTheGarbledManifest() throws Exception {
        try (Connection conn = adminDs.getConnection(); Statement s = conn.createStatement()) {
            s.execute("DELETE FROM core.workflow_manifest WHERE tenant_id = " + OWNER + " AND name = 'wf-pc-garbled'");
        }
    }

    /** A stored manifest the parser no longer accepts must abstain, not 500, and say nothing of itself. */
    @Test
    void a_stored_manifest_that_no_longer_parses_answers_exactly_what_an_absent_one_does() throws Exception {
        garble();

        assertThat(post(OWNER, "wf-pc-garbled", dossier())).isEqualTo(post(OWNER, "no-such-workflow", dossier()));
    }

    /** The operator is told which workflow broke, never what the YAML parser quoted from its text. */
    @Test
    void the_warning_for_an_unparseable_manifest_names_it_and_quotes_nothing_of_its_text() throws Exception {
        garble();

        List<String> warnings = LogProbe.warnings(ProposalCheckResource.class,
            () -> post(OWNER, "wf-pc-garbled", dossier()));

        assertThat(warnings).hasSize(1);                       // a probe that saw nothing proves nothing
        assertThat(warnings.get(0)).contains("wf-pc-garbled").doesNotContain("this is").doesNotContain("flow sequence");
    }

    /**
     * The catch around the lookup is for a manifest the parser refuses, not for the database being down:
     * the integrator is not told "no conclusion" about a dossier that was never checked (#811). A thrown
     * {@code RuntimeException} is what {@code Jdbc.queryOne} wraps an SQL failure in.
     */
    @Test
    void a_failing_store_is_a_500_not_a_clean_answer_with_no_conclusion() {
        doThrow(new RuntimeException("database down")).when(registry).findByName(anyLong(), anyString());

        as(OWNER).contentType("text/plain").queryParam("workflow", "wf-pc-good")
            .body(dossier().getBytes(StandardCharsets.UTF_8))
            .when().post("/api/proposal/check").then().statusCode(500);
    }

    private void garble() throws Exception {
        try (Connection conn = adminDs.getConnection(); Statement s = conn.createStatement()) {
            s.execute("INSERT INTO core.workflow_manifest (tenant_id, name, yaml_text, scope, on_table, on_column) VALUES ("
                + OWNER + ", 'wf-pc-garbled', 'kind: [this is: not { a workflow', 'demo', 'connector.t_prop_check_it', 'garbled_col') "
                + "ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text");
        }
    }

    @Test
    void a_contract_invalid_dossier_is_a_400_with_the_reason_and_no_echo() {
        as(OWNER).contentType("text/plain").body(DossierReaderTest.fixture("invalid/duplicate-key.md").getBytes(StandardCharsets.UTF_8))
            .when().post("/api/proposal/check")
            .then().statusCode(400).contentType("application/json")
            .body("error", equalTo("duplicate-key"))
            .body(not(containsString("commandes")));
    }

    /** The file is UTF-8 by contract; a client that omits the charset, or declares another, must not corrupt it. */
    @Test
    void a_dossier_is_decoded_as_utf8_whatever_charset_the_client_declares() {
        String answer = as(OWNER).contentType("text/markdown; charset=ISO-8859-1")
            .body(dossier().getBytes(StandardCharsets.UTF_8))
            .when().post("/api/proposal/check").then().statusCode(200).extract().asString();

        assertThat(asked(answer)).contains("terminé…");
    }

    /**
     * What a bare {@code curl --data-binary @dossier.md} sends: form-urlencoded, which RestAssured's own
     * encoder refuses for bytes, so this goes through the JDK client at the test port.
     */
    @Test
    void a_bare_curl_without_a_content_type_is_read_like_any_other_client() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(RestAssured.baseURI + ":" + RestAssured.port + "/api/proposal/check"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Tenant-ID", OWNER).header("X-User-ID", "1")
            .POST(HttpRequest.BodyPublishers.ofByteArray(dossier().getBytes(StandardCharsets.UTF_8))).build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(asked(new String(response.body(), StandardCharsets.UTF_8))).contains("terminé…");
    }

    /** A Latin-1 file (a Windows editor, a mangled copy): refused, never answered with U+FFFD in its place. */
    @Test
    void a_dossier_that_is_not_valid_utf8_is_refused_not_silently_corrupted() {
        as(OWNER).contentType("text/plain")
            .body(dossier().getBytes(StandardCharsets.ISO_8859_1))     // "é" becomes 0xE9, invalid UTF-8
            .when().post("/api/proposal/check")
            .then().statusCode(400).contentType("application/json")
            .body("error", equalTo("malformed-header"));
    }

    @Test
    void an_empty_body_is_a_400_not_a_500() {
        as(OWNER).contentType("text/plain").body("")
            .when().post("/api/proposal/check").then().statusCode(400);
    }

    /** The observable invariant: a check changes nothing the tenant owns and records no apply. */
    @Test
    void a_check_writes_no_business_row_and_no_workflow_apply_audit_line() throws Exception {
        String auditSql = "SELECT count(*) FROM core.audit_outbox WHERE tenant_id = " + OWNER
            + " AND entity_type = 'WorkflowManifest' AND operation = 'APPLY'";
        String manifestSql = "SELECT count(*) FROM core.workflow_manifest WHERE tenant_id = " + OWNER;
        long manifestsBefore = count(manifestSql);
        long auditBefore = count(auditSql);
        long rowsBefore = count("SELECT count(*) FROM connector.t_prop_check_it");

        check(OWNER, "wf-pc-good");
        check(OWNER, "wf-pc-nokey");

        assertThat(count(manifestSql)).isEqualTo(manifestsBefore);
        assertThat(count(auditSql)).isEqualTo(auditBefore);
        assertThat(count("SELECT count(*) FROM connector.t_prop_check_it")).isEqualTo(rowsBefore);
    }

    @Test
    void checking_the_answer_again_changes_nothing() {
        Dossier once = check(OWNER, "wf-pc-good");

        String again = post(OWNER, "wf-pc-good", DossierWriter.write(once));

        assertThat(again).isEqualTo(DossierWriter.write(once));
    }

    private long count(String sql) throws Exception {
        try (Connection conn = adminDs.getConnection(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
