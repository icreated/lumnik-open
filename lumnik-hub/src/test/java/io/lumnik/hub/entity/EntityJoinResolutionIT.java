package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertUndecidedIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

/**
 * #850: a {@code resolution:} entity with {@code joins[]} compiles, reads and answers like a {@code match:} one —
 * the slice-1 contract (0 → NULL, 1 → value, > 1 → NULL + ledger) holds on the resolved view, an undecided row is
 * kept, and the lookup is read under the caller's own tenant. RLS enforced: the lookup is a security_invoker join.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityJoinResolutionIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String JOINED = "resolved_category_label";

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntitySchemaBuilder schemas;

  record Fx(String entity, String products, String lookup) {}

  /** P1,P2 → C1 (decided, two entities); P3 → C9 (decided, no lookup row); P4 → C2 (decided, C2 listed twice); P5 → C1 undecided. */
  private Fx fixture(long tenant) throws Exception {
    String n = String.valueOf(System.nanoTime());
    String products = "t_jr_p_" + n, lookup = "t_jr_c_" + n, entity = "JoinResolution" + n;
    createTable(adminDs, products, "pid text, cid text, pname text");
    createTable(adminDs, lookup, "cid text, cname text");
    yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, JOINED))
        .post("/api/entities/apply").then().statusCode(200);
    for (String[] r : new String[][] {{"P1", "C1"}, {"P2", "C1"}, {"P3", "C9"}, {"P4", "C2"}, {"P5", "C1"}}) {
      exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
          + tenant + ", '" + r[0] + "', '" + r[1] + "', 'n" + r[0] + "')");
    }
    for (String p : new String[] {"P1", "P2", "P3", "P4"}) {
      insertIdentity(adminDs, tenant, entity, products, p, UUID.randomUUID());
    }
    insertUndecidedIdentity(adminDs, tenant, entity, products, "P5", "ambiguous");
    exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES ("
        + tenant + ", 'C1', 'Pumps'), (" + tenant + ", 'C2', 'Valves'), (" + tenant + ", 'C2', 'Valves old')");
    return new Fx(entity, products, lookup);
  }

  private JsonNode rows(long tenant, String entity) throws Exception {
    return MAPPER.readTree(given().headers(TestIdentity.tenantAndUser(tenant))
        .get("/api/entities/" + entity.toLowerCase(Locale.ROOT))
        .then().statusCode(200).extract().asString());
  }

  private static JsonNode row(JsonNode rows, String productId) {
    for (JsonNode r : rows) if (productId.equals(r.path("product_id").asText())) return r;
    throw new AssertionError("no row for " + productId + " in " + rows);
  }

  private static boolean nullish(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull();
  }

  @Test
  void the_slice_one_contract_holds_on_a_resolved_entity_and_an_undecided_row_is_kept() throws Exception {
    Fx f = fixture(850_101);
    JsonNode rows = rows(850_101, f.entity());

    assertThat(rows).as("one row per product, the undecided one included").hasSize(5);
    assertThat(row(rows, "P1").path(JOINED).asText()).isEqualTo("Pumps");
    assertThat(row(rows, "P2").path(JOINED).asText()).isEqualTo("Pumps");
    assertThat(nullish(row(rows, "P3").path(JOINED))).as("no lookup row: empty, not an opinion").isTrue();
    assertThat(nullish(row(rows, "P4").path(JOINED))).as("C2 is listed twice: ambiguous").isTrue();
    JsonNode ledger = MAPPER.readTree(row(rows, "P4").path("_conflicts").asText("{}")).path(JOINED);
    assertThat(ledger).as("one ledger entry per duplicate row").hasSize(2);
    assertThat(row(rows, "P5").path(JOINED).asText()).as("an undecided row still reads its lookup").isEqualTo("Pumps");
  }

  @Test
  void the_lookup_is_read_under_the_callers_tenant_only() throws Exception {
    Fx f = fixture(850_102);
    // Another tenant lists C1 too. Were the lookup not isolated, C1 would become ambiguous for tenant 850102.
    exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850103, 'C1', 'Other')");

    assertThat(row(rows(850_102, f.entity()), "P1").path(JOINED).asText()).isEqualTo("Pumps");
  }

  /**
   * #928: a row nothing has resolved whose lookup is ambiguous — the common unresolved conflict — is listed by the
   * aggregate under its source handle, on a joined view too (the view must carry {@code _handle} through the join).
   */
  @Test
  void an_unresolved_row_with_an_ambiguous_lookup_is_listed_under_its_source_handle() throws Exception {
    Fx f = fixture(850_106);
    exec(adminDs, "INSERT INTO connector." + f.products() + " (tenant_id, pid, cid, pname) VALUES (850106, 'P6', 'C2', 'n6')");
    insertUndecidedIdentity(adminDs, 850_106, f.entity(), f.products(), "P6", "ambiguous");

    given().headers(TestIdentity.tenantAndUser(850_106))
        .get("/api/entities/conflicts?limit=1000")
        .then().statusCode(200)
        .body("findAll { it.entity == '" + f.entity() + "' && it.key == '" + f.products() + ":P6' }", hasSize(1));
  }

  /**
   * The entity read door exposes canonical columns and the ledger only, so it cannot see the columns the Search worker
   * keys a resolved document by: the joined view must still carry {@code _entity_id} and {@code _resolution} (read
   * directly on the admin pool; the fixture's tenant is the only one with rows in these tables).
   */
  @Test
  void the_joined_view_still_carries_the_identity_and_resolution_columns() throws Exception {
    Fx f = fixture(850_105);
    Map<String, String> byProduct = new TreeMap<>();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT product_id, _entity_id::text, _resolution FROM connector.\"v_"
             + f.entity().toLowerCase(Locale.ROOT) + "\"")) {
      while (rs.next()) byProduct.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3));
    }

    assertThat(byProduct).containsOnlyKeys("P1", "P2", "P3", "P4", "P5");
    for (String decided : new String[] {"P1", "P2", "P3", "P4"}) {
      assertThat(byProduct.get(decided)).as(decided).matches("[0-9a-f-]{36}\\|resolved");
    }
    assertThat(byProduct.get("P5")).as("an undecided row has no identity").isEqualTo("null|ambiguous");
  }

  @Test
  void ask_advertises_the_joined_column_of_a_resolved_entity() throws Exception {
    fixture(850_104);
    assertThat(String.valueOf(schemas.resolve("lk"))).contains(JOINED);
  }
}
