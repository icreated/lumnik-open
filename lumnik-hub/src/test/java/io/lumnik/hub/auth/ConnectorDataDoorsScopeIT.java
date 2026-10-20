package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #1212: the doors that reach a connector's data without naming it in their path — dead letters,
 * runs and the source doors — ask the rule #1208 put on the by-id doors, {@code allowsAll(tags)}.
 * A list narrows itself to the connectors the caller holds; an id of another métier's is the
 * uniform 403; a creating door refuses tags the caller does not all hold, an empty list included.
 * An unbound caller administers the whole tenant, as before.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ConnectorDataDoorsScopeIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private UUID named(String name, String... tags) throws Exception {
    UUID id = UUID.randomUUID();
    String array = tags.length == 0 ? "ARRAY[]::text[]" : "ARRAY['" + String.join("','", tags) + "']";
    sql("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
        + id + "', 1, '" + name + "', 'csv-file', '{}'::jsonb, true, " + array + ")");
    return id;
  }

  private UUID connector(String... tags) throws Exception {
    return named("data-doors-" + UUID.randomUUID(), tags);
  }

  private UUID run(UUID connector) throws Exception {
    UUID id = UUID.randomUUID();
    sql("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by, "
        + "error_message) VALUES ('" + id + "', 1, '" + connector + "', 'Completed', 'full', 'manual', 'boom')");
    return id;
  }

  private UUID deadLetter(UUID connector, UUID run) throws Exception {
    UUID id = UUID.randomUUID();
    sql("INSERT INTO connector.connector_dead_letter (id, tenant_id, connector_id, run_id, payload, skip_reason) "
        + "VALUES ('" + id + "', 1, '" + connector + "', '" + run + "', '{\"iban\":\"secret\"}'::jsonb, 'MappingFailed')");
    return id;
  }

  private String resolution(UUID deadLetter) throws Exception {
    return single("SELECT resolution FROM connector.connector_dead_letter WHERE id = '" + deadLetter + "'");
  }

  private String tagsOf(String name) throws Exception {
    return single("SELECT array_to_string(tags, ',') FROM connector.connector WHERE tenant_id = 1 AND name = '" + name + "'");
  }

  private long connectorsNamed(String like) throws Exception {
    return Long.parseLong(single("SELECT count(*) FROM connector.connector WHERE tenant_id = 1 AND name LIKE '" + like + "'"));
  }

  private void sql(String statement) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute(statement);
    }
  }

  private String single(String query) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(query)) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }

  private static RequestSpecification as(String... scopes) {
    Set<String> roles = new HashSet<>(Set.of("lm_admin", "lm_integrator"));
    for (String s : scopes) roles.add("scope:" + s);
    return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null));
  }

  private static RequestSpecification json(String... scopes) {
    return as(scopes).header("Content-Type", "application/json");
  }

  private static void refused(Response r) {
    r.then().statusCode(403).body("error", equalTo("scope not granted"));
  }

  private static List<String> ids(Response r) {
    return r.then().statusCode(200).extract().jsonPath().getList("id", String.class);
  }

  // ── dead letters ─────────────────────────────────────────────────────────────

  @Test
  void a_bound_integrator_neither_reads_nor_resolves_another_metiers_dead_letters() throws Exception {
    UUID other = connector("magasin");
    UUID run = run(other);
    UUID dl = deadLetter(other, run);
    String d = "/api/platform/dlq/" + dl;

    refused(as("catalogue").get(d));
    refused(as("catalogue").post(d + "/discard"));
    refused(as("catalogue").post(d + "/replayed"));
    refused(as("catalogue").get("/api/platform/dlq?connector=" + other));
    refused(as("catalogue").get("/api/platform/dlq?run=" + run));
    assertThat(ids(as("catalogue").get("/api/platform/dlq?limit=200"))).doesNotContain(dl.toString());

    assertThat(resolution(dl)).as("nothing was discarded or marked replayed").isNull();
  }

  @Test
  void the_dead_letter_list_holds_a_bound_callers_own_and_an_unbound_callers_all() throws Exception {
    UUID own = connector("catalogue"), other = connector("magasin");
    UUID mine = deadLetter(own, run(own)), theirs = deadLetter(other, run(other));

    assertThat(ids(as("catalogue").get("/api/platform/dlq?limit=200")))
        .contains(mine.toString()).doesNotContain(theirs.toString());
    assertThat(ids(as().get("/api/platform/dlq?limit=200"))).contains(mine.toString(), theirs.toString());

    as("catalogue").get("/api/platform/dlq/" + mine).then().statusCode(200);
    as("catalogue").post("/api/platform/dlq/" + mine + "/discard").then().statusCode(204);
    assertThat(resolution(mine)).isEqualTo("DISCARDED");
  }

  // ── runs ─────────────────────────────────────────────────────────────────────

  @Test
  void a_bound_integrator_neither_reads_nor_cancels_another_metiers_runs() throws Exception {
    UUID other = connector("magasin");
    UUID r = run(other);

    refused(as("catalogue").get("/api/platform/runs/" + r));
    refused(as("catalogue").post("/api/platform/runs/" + r + "/cancel"));
    refused(as("catalogue").get("/api/platform/runs?connector=" + other));
    assertThat(ids(as("catalogue").get("/api/platform/runs?limit=200"))).doesNotContain(r.toString());
  }

  @Test
  void the_run_list_holds_a_bound_callers_own_and_an_unbound_callers_all() throws Exception {
    UUID own = connector("catalogue"), other = connector("magasin"), none = connector();
    UUID mine = run(own), theirs = run(other), scopeless = run(none);

    assertThat(ids(as("catalogue").get("/api/platform/runs?limit=200")))
        .contains(mine.toString()).doesNotContain(theirs.toString(), scopeless.toString());
    assertThat(ids(as().get("/api/platform/runs?limit=200")))
        .contains(mine.toString(), theirs.toString(), scopeless.toString());
    as("catalogue").get("/api/platform/runs/" + mine).then().statusCode(200);
  }

  @Test
  void an_unknown_run_is_a_404_to_a_bound_caller_too() {
    as("catalogue").get("/api/platform/runs/" + UUID.randomUUID()).then().statusCode(404);
  }

  // ── source doors ─────────────────────────────────────────────────────────────

  @Test
  void the_guided_jdbc_add_refuses_a_bound_caller_the_scopeless_connectors_it_creates() throws Exception {
    String source = "doors-jdbc-" + System.nanoTime();
    refused(json("catalogue").body("{\"sourceName\":\"" + source + "\",\"url\":\"jdbc:postgresql://127.0.0.1:1/x\","
        + "\"user\":\"u\",\"password\":\"p\",\"tables\":[{\"table\":\"t\"}]}").post("/api/platform/sources/jdbc/add"));
    assertThat(connectorsNamed(source + "--%")).isZero();
  }

  @Test
  void the_jdbc_source_table_list_holds_only_the_connectors_the_caller_holds() throws Exception {
    String source = "doors-src-" + System.nanoTime();
    named(source + "--mine", "catalogue");
    named(source + "--theirs", "magasin");

    List<String> tables = as("catalogue").get("/api/platform/sources/jdbc/" + source + "/tables")
        .then().statusCode(200).extract().jsonPath().getList("tables.table", String.class);
    assertThat(tables).containsExactly("mine");
  }

  @Test
  void the_rest_probe_refuses_another_metiers_connector() throws Exception {
    refused(json("catalogue").body("{\"connectorId\":\"" + connector("magasin") + "\"}")
        .post("/api/platform/sources/rest/probe"));
    json("catalogue").body("{\"connectorId\":\"" + UUID.randomUUID() + "\"}")
        .post("/api/platform/sources/rest/probe").then().statusCode(404);
  }

  @Test
  void the_csv_one_shot_creates_only_inside_the_callers_scopes() throws Exception {
    File csv = csv();
    String n = "doors_csv_" + System.nanoTime();

    refused(as("catalogue").multiPart("file", csv).multiPart("name", n + "_a").multiPart("scope", "magasin")
        .post("/api/platform/sources/csv/ingest"));
    refused(as("catalogue").multiPart("file", csv).multiPart("name", n + "_b")
        .post("/api/platform/sources/csv/ingest"));
    assertThat(connectorsNamed(n + "%")).as("no one-shot was created").isZero();

    as("catalogue").multiPart("file", csv).multiPart("name", n + "_c").multiPart("scope", "catalogue")
        .post("/api/platform/sources/csv/ingest").then().statusCode(200);
  }

  @Test
  void a_csv_re_ingest_does_not_take_over_another_metiers_one_shot() throws Exception {
    File csv = csv();
    String n = "doors_breath_" + System.nanoTime();
    as().multiPart("file", csv).multiPart("name", n).multiPart("scope", "magasin")
        .post("/api/platform/sources/csv/ingest").then().statusCode(200);

    refused(as("catalogue").multiPart("file", csv).multiPart("name", n).multiPart("scope", "catalogue")
        .post("/api/platform/sources/csv/ingest"));
    assertThat(tagsOf(n)).as("the one-shot stays in its métier").isEqualTo("magasin");
  }

  @Test
  void a_csv_re_ingest_through_a_transport_is_refused_the_same_way() throws Exception {
    // The transport path wraps each file's failure: the refusal must cross it as the uniform 403.
    File csv = csv();
    String n = "doors_transport_" + System.nanoTime();
    as().multiPart("file", csv).multiPart("name", n).multiPart("scope", "magasin")
        .post("/api/platform/sources/csv/ingest").then().statusCode(200);

    refused(as("catalogue").multiPart("transport", "{\"kind\":\"local\",\"path\":\"" + csv.getAbsolutePath() + "\"}")
        .multiPart("name", n).multiPart("scope", "catalogue").post("/api/platform/sources/csv/ingest"));
    assertThat(tagsOf(n)).isEqualTo("magasin");
  }

  @Test
  void a_csv_re_ingest_judges_the_tags_a_concurrent_retag_commits_not_the_ones_it_read_before()
      throws Exception {
    // #1216 review: the decision must hold until the write. A retag in flight when the re-ingest
    // starts either lands first and is judged, or waits for the re-ingest — never overwritten.
    File csv = csv();
    String n = "doors_race_" + System.nanoTime();
    as().multiPart("file", csv).multiPart("name", n).multiPart("scope", "catalogue")
        .post("/api/platform/sources/csv/ingest").then().statusCode(200);

    try (Connection retag = adminDs.getConnection()) {
      retag.setAutoCommit(false);
      try (Statement st = retag.createStatement()) {
        st.executeUpdate("UPDATE connector.connector SET tags = ARRAY['magasin'] WHERE tenant_id = 1 AND name = '" + n + "'");
      }
      CompletableFuture<Response> reIngest = CompletableFuture.supplyAsync(() ->
          as("catalogue").multiPart("file", csv).multiPart("name", n).multiPart("scope", "catalogue")
              .post("/api/platform/sources/csv/ingest"));
      Thread.sleep(1500);   // the re-ingest reaches the row the retag holds
      assertThat(reIngest).as("the re-ingest waits for the retag in flight").isNotDone();
      retag.commit();
      refused(reIngest.get(30, TimeUnit.SECONDS));
    }
    assertThat(tagsOf(n)).as("the retag stands").isEqualTo("magasin");
  }

  @Test
  void another_tenants_connector_is_a_404_even_to_an_unbound_integrator() throws Exception {
    UUID id = UUID.randomUUID();
    sql("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
        + id + "', 2, 'data-doors-t2-" + id + "', 'csv-file', '{}'::jsonb, true, ARRAY[]::text[])");
    as().get("/api/platform/connectors/" + id).then().statusCode(404);
    json().body("{\"connectorId\":\"" + id + "\"}").post("/api/platform/sources/rest/probe").then().statusCode(404);
  }

  @Test
  void another_tenants_dead_letter_is_a_404_and_stays_unresolved() throws Exception {
    // #1216 review: discard and replayed delegated even when the tenant's lookup found nothing,
    // and the capture's own findById bypasses the tenant @Filter.
    UUID connector = UUID.randomUUID(), run = UUID.randomUUID(), dl = UUID.randomUUID();
    sql("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
        + connector + "', 2, 'data-doors-t2-" + connector + "', 'csv-file', '{}'::jsonb, true, ARRAY[]::text[])");
    sql("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by) "
        + "VALUES ('" + run + "', 2, '" + connector + "', 'Completed', 'full', 'manual')");
    sql("INSERT INTO connector.connector_dead_letter (id, tenant_id, connector_id, run_id, payload, skip_reason) "
        + "VALUES ('" + dl + "', 2, '" + connector + "', '" + run + "', '{}'::jsonb, 'MappingFailed')");

    as().post("/api/platform/dlq/" + dl + "/discard").then().statusCode(404);
    as().post("/api/platform/dlq/" + dl + "/replayed").then().statusCode(404);
    assertThat(resolution(dl)).isNull();
  }

  private static File csv() throws Exception {
    File f = Files.createTempFile("doors_", ".csv").toFile();
    Files.writeString(f.toPath(), "id,email\n1,a@x\n");
    f.deleteOnExit();
    return f;
  }
}
