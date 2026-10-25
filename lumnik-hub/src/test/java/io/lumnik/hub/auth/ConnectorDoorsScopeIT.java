package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.RivalConnectorWrite;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #1208: a connector lives in the scopes its tags name, and every door acting on one by id asks
 * {@code allowsAll(connector.tags)} — the write rule, because each of them can change, run or delete
 * it. A caller bound to {@code scope:*} roles is refused a connector of another scope, a connector
 * spanning a scope they do not hold, and a scopeless one; the list shows only the connectors they
 * hold. An unbound caller administers the whole tenant, as before.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ConnectorDoorsScopeIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private UUID connector(String... tags) throws Exception {
    UUID id = UUID.randomUUID();
    String array = tags.length == 0 ? "ARRAY[]::text[]" : "ARRAY['" + String.join("','", tags) + "']";
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
          + id + "', 1, 'doors-" + id + "', 'csv-file', '{}'::jsonb, true, " + array + ")");
    }
    return id;
  }

  private boolean enabled(UUID id) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT enabled FROM connector.connector WHERE id = '" + id + "'")) {
      assertThat(rs.next()).as("connector %s still exists", id).isTrue();
      return rs.getBoolean(1);
    }
  }

  private static RequestSpecification as(String... scopes) {
    Set<String> roles = new HashSet<>(Set.of("lm_admin", "lm_integrator"));
    for (String s : scopes) roles.add("scope:" + s);
    return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null))
        .header("Content-Type", "application/json");
  }

  private static void refused(Response r) {
    r.then().statusCode(403).body("error", equalTo("scope not granted"));
  }

  private static String body(String name, String tags) {
    return "{\"tenantId\":1,\"name\":\"" + name + "\",\"typeId\":\"csv-file\",\"enabled\":true,"
        + "\"config\":{}" + (tags == null ? "" : ",\"tags\":" + tags) + "}";
  }

  @Test
  void a_bound_integrator_is_refused_every_door_of_another_metiers_connector() throws Exception {
    UUID id = connector("catalogue");
    String c = "/api/platform/connectors/" + id, ep = c + "/endpoints/" + UUID.randomUUID();

    refused(as("magasin").get(c));
    refused(as("magasin").body(body("renamed", null)).put(c));
    refused(as("magasin").post(c + "/run"));
    refused(as("magasin").post(c + "/pause"));
    refused(as("magasin").post(c + "/resume"));
    refused(as("magasin").get(c + "/tags"));
    refused(as("magasin").body("[\"magasin\"]").put(c + "/tags"));
    refused(as("magasin").post(c + "/retag"));
    refused(as("magasin").get(c + "/endpoints"));
    refused(as("magasin").get(ep));
    refused(as("magasin").body("{}").put(ep));
    refused(as("magasin").delete(ep));
    refused(as("magasin").post(ep + "/test"));
    refused(as("magasin").post(ep + "/reset-cursor"));
    refused(as("magasin").post(ep + "/purge"));
    refused(as("magasin").delete(c));

    assertThat(enabled(id)).as("nothing was paused, renamed or deleted").isTrue();
  }

  @Test
  void a_connector_spanning_a_metier_the_caller_does_not_hold_is_refused() throws Exception {
    refused(as("catalogue").get("/api/platform/connectors/" + connector("catalogue", "magasin")));
  }

  @Test
  void a_scopeless_connector_is_out_of_a_bound_callers_reach() throws Exception {
    refused(as("catalogue").get("/api/platform/connectors/" + connector()));
  }

  @Test
  void an_unknown_id_is_a_404_to_a_bound_caller_too() {
    as("catalogue").get("/api/platform/connectors/" + UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  void the_list_shows_a_bound_caller_only_the_connectors_they_hold() throws Exception {
    UUID own = connector("catalogue"), other = connector("magasin"),
        both = connector("catalogue", "magasin"), none = connector();

    List<String> seen = as("catalogue").get("/api/platform/connectors")
        .then().statusCode(200).extract().jsonPath().getList("id", String.class);
    assertThat(seen).contains(own.toString())
        .doesNotContain(other.toString(), both.toString(), none.toString());

    List<String> all = as().get("/api/platform/connectors")
        .then().statusCode(200).extract().jsonPath().getList("id", String.class);
    assertThat(all).contains(own.toString(), other.toString(), both.toString(), none.toString());
  }

  @Test
  void a_bound_caller_holding_the_connector_keeps_its_doors() throws Exception {
    UUID id = connector("catalogue");
    String c = "/api/platform/connectors/" + id;
    as("catalogue").get(c).then().statusCode(200);
    as("catalogue").get(c + "/tags").then().statusCode(200);
    as("catalogue").post(c + "/pause").then().statusCode(200);
    assertThat(enabled(id)).isFalse();
  }

  @Test
  void a_bound_caller_cannot_move_a_connector_into_a_metier_they_do_not_hold() throws Exception {
    UUID id = connector("catalogue");
    String c = "/api/platform/connectors/" + id;
    refused(as("catalogue").body("[\"magasin\"]").put(c + "/tags"));
    refused(as("catalogue").body("[]").put(c + "/tags"));
    refused(as("catalogue").body(body("doors-moved-" + id, "[\"catalogue\",\"magasin\"]")).put(c));
  }

  @Test
  void the_tags_door_answers_for_the_connector_before_judging_the_body() throws Exception {
    // #1213 review: the tag names were validated first, so a malformed body answered 400 where the
    // id alone decides — another scope's connector is the uniform 403, an unknown one a 404
    String malformed = "[\"Not A Scope\"]";
    refused(as("magasin").body(malformed).put("/api/platform/connectors/" + connector("catalogue") + "/tags"));
    as("magasin").body(malformed).put("/api/platform/connectors/" + UUID.randomUUID() + "/tags")
        .then().statusCode(404);
  }

  @Test
  void a_bound_caller_creates_only_inside_their_scopes() {
    String n = String.valueOf(System.nanoTime());
    refused(as("catalogue").body(body("doors-new-a-" + n, "[\"magasin\"]")).post("/api/platform/connectors"));
    refused(as("catalogue").body(body("doors-new-b-" + n, null)).post("/api/platform/connectors"));
    as("catalogue").body(body("doors-new-c-" + n, "[\"catalogue\"]")).post("/api/platform/connectors")
        .then().statusCode(201);
  }

  /**
   * #1224: the name's holder is named by a 409 only to a caller who holds it. Another scope's
   * holder is the uniform refusal — the one bit the manifest door already gives (ApplyScopeBoundary),
   * never its id.
   */
  @Test
  void a_name_taken_in_another_metier_is_the_uniform_refusal_not_a_409_naming_it() throws Exception {
    UUID other = connector("magasin");
    UUID own = connector("catalogue");
    String c = "/api/platform/connectors/" + own;
    refused(as("catalogue").body(body("doors-" + other, "[\"catalogue\"]")).post("/api/platform/connectors"));
    refused(as("catalogue").body(body("doors-" + other, null)).put(c));
    as("catalogue").body(body("doors-" + own, "[\"catalogue\"]")).post("/api/platform/connectors")
        .then().statusCode(409).body("id", equalTo(own.toString()));
  }

  // ── #1231: two writes racing onto one free name — the loser waits and is answered ────────────────

  private static Response apply(RequestSpecification caller, String name, String scope) {
    return caller.config(YamlBody.CONFIG).contentType("application/yaml").body("""
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: %s
          connector_type: csv-file
          %s
        spec:
          transport: { kind: local, path: /tmp/race.csv }
          parser: { has_header: true }
          after_process: none
          target: {}
        """.formatted(name, scope == null ? "" : "scopes: [" + scope + "]"))
        .post("/api/platform/connectors/apply");
  }

  private static String freeName() {
    return "doors-race-" + UUID.randomUUID();
  }

  @Test
  void a_create_or_rename_racing_onto_a_free_name_is_the_409_naming_the_holder() throws Exception {
    UUID own = connector();
    String created = freeName(), renamed = freeName();
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    try (var rival = new RivalConnectorWrite(adminDs, first, created)) {
      rival.answerAfterCommit(() -> as().body(body(created, null)).post("/api/platform/connectors"))
          .then().statusCode(409).body("id", equalTo(first.toString()));
    }
    try (var rival = new RivalConnectorWrite(adminDs, second, renamed)) {
      rival.answerAfterCommit(() -> as().body(body(renamed, null)).put("/api/platform/connectors/" + own))
          .then().statusCode(409).body("id", equalTo(second.toString()));
    }
    assertThat(single("SELECT name FROM connector.connector WHERE id = '" + own + "'")).isEqualTo("doors-" + own);
  }

  @Test
  void a_create_or_rename_racing_onto_another_metiers_holder_is_the_uniform_refusal() throws Exception {
    UUID own = connector("catalogue");
    String created = freeName(), renamed = freeName();
    try (var rival = new RivalConnectorWrite(adminDs, UUID.randomUUID(), created, "magasin")) {
      refused(rival.answerAfterCommit(
          () -> as("catalogue").body(body(created, "[\"catalogue\"]")).post("/api/platform/connectors")));
    }
    try (var rival = new RivalConnectorWrite(adminDs, UUID.randomUUID(), renamed, "magasin")) {
      refused(rival.answerAfterCommit(
          () -> as("catalogue").body(body(renamed, null)).put("/api/platform/connectors/" + own)));
    }
  }

  @Test
  void a_manifest_apply_racing_onto_a_free_name_updates_the_holder_it_waited_for() throws Exception {
    String name = freeName(), foreign = freeName();
    UUID holder = UUID.randomUUID();
    try (var rival = new RivalConnectorWrite(adminDs, holder, name)) {
      rival.answerAfterCommit(() -> apply(as(), name, null))
          .then().statusCode(200).body("connectorId", equalTo(holder.toString()));
    }
    try (var rival = new RivalConnectorWrite(adminDs, UUID.randomUUID(), foreign, "magasin")) {
      refused(rival.answerAfterCommit(() -> apply(as("catalogue"), foreign, "catalogue")));
    }
  }

  @Test
  void an_unbound_integrator_keeps_tenant_wide_administration() throws Exception {
    UUID id = connector();
    as().get("/api/platform/connectors/" + id).then().statusCode(200);
    as().body("[\"magasin\"]").put("/api/platform/connectors/" + id + "/tags").then().statusCode(200);
  }

  // ── #1217: a door that writes decides on the row it holds ───────────────────────────────────────

  /** The writing doors on a connector, each given the connector's path. */
  static Stream<Arguments> writingDoors() {
    return Stream.of(
        door("PUT …/tags", c -> as("catalogue").body("[\"catalogue\"]").put(c + "/tags")),
        door("PUT /connectors/{id} without tags",
            c -> as("catalogue").body(body("doors-renamed-" + c.substring(c.lastIndexOf('/') + 1), null)).put(c)),
        door("pause", c -> as("catalogue").post(c + "/pause")),
        door("resume", c -> as("catalogue").post(c + "/resume")),
        door("delete", c -> as("catalogue").delete(c)));
  }

  private static Arguments door(String name, Function<String, Response> door) {
    return Arguments.of(Named.of(name, door));
  }

  @ParameterizedTest
  @MethodSource("writingDoors")
  void a_writing_door_judges_the_tags_a_concurrent_retag_commits_not_the_ones_it_read_before(
      Function<String, Response> door) throws Exception {
    UUID id = connector("catalogue");
    judgedOnTheRetagItWaitedFor(id, () -> door.apply("/api/platform/connectors/" + id));
    assertThat(tags(id)).as("the retag stands").isEqualTo("magasin");
  }

  @Test
  void an_endpoint_write_judges_the_tags_a_concurrent_retag_commits() throws Exception {
    UUID id = connector("catalogue"), ep = UUID.randomUUID();
    sql("INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, name, path, config) VALUES ('"
        + ep + "', '" + id + "', 1, 'ep', '/ep', '{}'::jsonb)");
    judgedOnTheRetagItWaitedFor(id,
        () -> as("catalogue").delete("/api/platform/connectors/" + id + "/endpoints/" + ep));
    assertThat(single("SELECT deleted_at IS NULL FROM connector.connector_endpoint WHERE id = '" + ep + "'"))
        .as("the endpoint was not deleted").isEqualTo("t");
  }

  /**
   * A retag moving the connector to {@code magasin} is held open; the door's request starts and
   * must wait for it; the retag commits; the door, called by a {@code catalogue} caller, is judged
   * on the committed tags — the uniform 403 — rather than on the ones it could read before.
   */
  private void judgedOnTheRetagItWaitedFor(UUID id, Supplier<Response> door) throws Exception {
    try (Connection retag = adminDs.getConnection()) {
      retag.setAutoCommit(false);
      try (Statement st = retag.createStatement()) {
        st.executeUpdate("UPDATE connector.connector SET tags = ARRAY['magasin'] WHERE id = '" + id + "'");
      }
      CompletableFuture<Response> request = CompletableFuture.supplyAsync(door);
      Thread.sleep(1500);   // the door reaches the row the retag holds
      assertThat(request).as("the door waits for the retag in flight").isNotDone();
      retag.commit();
      refused(request.get(30, TimeUnit.SECONDS));
    }
  }

  private String tags(UUID id) throws Exception {
    return single("SELECT array_to_string(tags, ',') FROM connector.connector WHERE id = '" + id + "'");
  }

  private void sql(String statement) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute(statement);
    }
  }

  private String single(String query) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(query)) {
      assertThat(rs.next()).as("a row for %s", query).isTrue();
      return rs.getString(1);
    }
  }
}
