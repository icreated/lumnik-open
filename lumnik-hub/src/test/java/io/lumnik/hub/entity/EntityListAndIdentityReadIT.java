package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertUndecidedIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1114: {@code GET /api/entities} lists the entities the caller may read, so a client can browse
 * them instead of guessing a name. #1116: {@code ?identity=true} adds a resolution entity's
 * {@code _entity_id}, {@code _resolution} and {@code _handle} to each row — the handle being the
 * {@code SOURCE:ID} an assertion names — while the default read stays canonical columns + ledger.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityListAndIdentityReadIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private String fixture(long tenant) throws Exception {
    String n = String.valueOf(System.nanoTime());
    String products = "t_lir_p_" + n, lookup = "t_lir_c_" + n, entity = "ListIdentityRead" + n;
    createTable(adminDs, products, "pid text, cid text, pname text");
    createTable(adminDs, lookup, "cid text, cname text");
    yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_label"))
        .post("/api/entities/apply").then().statusCode(200);
    exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
        + tenant + ", 'P1', 'C1', 'n1'), (" + tenant + ", 'P2', 'C1', 'n2')");
    insertIdentity(adminDs, tenant, entity, products, "P1", UUID.randomUUID());
    insertUndecidedIdentity(adminDs, tenant, entity, products, "P2", "ambiguous");
    return entity;
  }

  private JsonNode get(long tenant, String path) throws Exception {
    return MAPPER.readTree(given().headers(TestIdentity.tenantAndUser(tenant)).get(path)
        .then().statusCode(200).extract().asString());
  }

  @Test
  void the_list_names_each_readable_entity_and_says_whether_it_resolves() throws Exception {
    String entity = fixture(1_114_001);
    JsonNode found = null;
    for (JsonNode e : get(1_114_001, "/api/entities")) {
      if (entity.equalsIgnoreCase(e.path("name").asText())) found = e;
    }
    assertThat(found).as("the applied entity is listed").isNotNull();
    assertThat(found.path("resolution").asBoolean()).isTrue();
    assertThat(found.path("sources")).hasSize(1);
  }

  @Test
  void identity_true_adds_the_identity_columns_and_the_default_read_does_not() throws Exception {
    String entity = fixture(1_116_001);
    String path = "/api/entities/" + entity.toLowerCase(Locale.ROOT);

    for (JsonNode r : get(1_116_001, path)) {
      assertThat(r.has("_handle")).as("the default read is unchanged").isFalse();
    }
    JsonNode rows = get(1_116_001, path + "?identity=true");
    assertThat(rows).hasSize(2);
    int resolved = 0, unresolved = 0;
    for (JsonNode r : rows) {
      assertThat(r.has("_entity_id") && r.has("_resolution") && r.has("_handle")).isTrue();
      // What a client names a row by: a resolved row by its entity id (handle NULL), an unresolved
      // one by its SOURCE:ID handle (entity id NULL) — lm ui's assertions rely on exactly this.
      if (!r.path("_entity_id").isNull()) {
        assertThat(r.path("_handle").isNull()).isTrue();
        resolved++;
      } else {
        assertThat(r.path("_handle").asText()).contains(":P2");
        unresolved++;
      }
    }
    assertThat(resolved).isEqualTo(1);
    assertThat(unresolved).isEqualTo(1);
  }
}
