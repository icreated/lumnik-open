package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1915: what rotating {@code LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET} does to rows that are already
 * resolved. One entity, two sources, three customers present in both: a first replay links each pair,
 * then the active key is swapped (the secret alone, then the secret with a new key id) and the replay
 * runs again. The tests record what the integrator sees — the replay's own counters, the crosswalk
 * states and the entity ids.
 *
 * <p>The key is swapped on the live {@link FingerprintHmacKey} bean (same package, fields are
 * package-private) and put back after each test, so no second Quarkus profile is needed.
 */
@QuarkusTest
class EntityResolutionHmacRotationIT {

  @Inject EntityResolutionPass pass;
  @Inject EntityRegistry registry;
  @Inject FingerprintHmacKey hmacKey;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "hmacrot1915";
  private static final String ERP = "t_hmacrot1915_erp";
  private static final String CRM = "t_hmacrot1915_crm";
  private static final AtomicLong NEXT_TENANT = new AtomicLong(98_500);

  private static final String MANIFEST = """
      kind: Entity
      name: HmacRot1915
      sources:
        - table: t_hmacrot1915_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
        - table: t_hmacrot1915_crm
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_hmacrot1915_erp: customer_id
          t_hmacrot1915_crm: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  private FingerprintHmacKey live;
  private String originalKeyId;
  private String originalSecret;

  @BeforeEach
  void setUp() throws Exception {
    live = ClientProxy.unwrap(hmacKey);
    originalKeyId = live.keyId;
    originalSecret = live.secret;
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String table : new String[] {ERP, CRM}) {
        st.execute("DROP TABLE IF EXISTS connector." + table);
        st.execute("CREATE TABLE connector." + table + " ("
            + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
            + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
      }
      st.execute("DELETE FROM core.entity_assertion WHERE entity_type = '" + ENTITY_TYPE + "'");
      st.execute("DELETE FROM core.entity_identity WHERE entity_type = '" + ENTITY_TYPE + "'");
    }
    registry.put(new EntityManifestParser().parse(MANIFEST));
  }

  @AfterEach
  void restoreKey() {
    live.keyId = originalKeyId;
    live.secret = originalSecret;
    live.init();
  }

  private void rotate(String keyId, String secret) {
    live.keyId = keyId;
    live.secret = secret;
    live.init();
  }

  private void seed(long tenant) throws Exception {
    for (int i = 1; i <= 3; i++) {
      insertRow(ERP, tenant, "e" + i, "c" + i + "@example.com");
      insertRow(CRM, tenant, "m" + i, "c" + i + "@example.com");
    }
  }

  private void insertRow(String table, long tenant, String customerId, String email) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("INSERT INTO connector." + table
             + " (tenant_id, \"CUSTOMER_ID\", \"EMAIL_COL\") VALUES (?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, customerId);
      ps.setString(3, email);
      ps.executeUpdate();
    }
  }

  /** source_id -> "state/entity_id" for every crosswalk row of the tenant. */
  private Map<String, String> crosswalk(long tenant) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT source_id, state, entity_id "
             + "FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ? ORDER BY source_id")) {
      ps.setLong(1, tenant);
      ps.setString(2, ENTITY_TYPE);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.put(rs.getString(1), rs.getString(2) + "/" + rs.getObject(3));
      }
    }
    return out;
  }

  private long countState(Map<String, String> cw, String state) {
    return cw.values().stream().filter(v -> v.startsWith(state + "/")).count();
  }

  @Inject CascadeResolver resolver;

  private static final String ROTATED = "a-completely-different-secret-value";

  private void assertEverySixRowsAmbiguousAndUnlinked(long tenant, EntityResolutionPass.PassSummary summary)
      throws Exception {
    assertThat(summary.resolved()).isZero();
    assertThat(summary.ambiguous()).isEqualTo(6);
    assertThat(summary.newlyMinted()).isZero();
    assertThat(summary.rowsProcessed()).isEqualTo(6);
    Map<String, String> cw = crosswalk(tenant);
    assertThat(countState(cw, "ambiguous")).isEqualTo(6);
    assertThat(cw.values()).allMatch(v -> v.equals("ambiguous/null"));
  }

  @Test
  void a_replay_under_the_same_key_links_every_row_the_same_way() throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");
    Map<String, String> before = crosswalk(tenant);

    EntityResolutionPass.PassSummary again = pass.runReplay(tenant, "HmacRot1915");

    assertThat(again.resolved()).isEqualTo(6);
    assertThat(again.newlyMinted()).isZero();
    assertThat(crosswalk(tenant)).isEqualTo(before);
    assertThat(before.get("e1")).isEqualTo(before.get("m1"));
  }

  /** The reading in #1915, run: the secret alone changes, the key id stays, so rule_version is the
   *  same — yet no stored fingerprint matches, and the guard against recycling a resolved link
   *  turns every row ambiguous. A second replay does not mend it. */
  @Test
  void rotating_the_secret_alone_turns_every_resolved_row_ambiguous_and_a_second_replay_does_not_mend_it()
      throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");
    assertThat(countState(crosswalk(tenant), "resolved")).isEqualTo(6);

    rotate(originalKeyId, ROTATED);

    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));
    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));
  }

  @Test
  void rotating_the_secret_with_a_new_key_id_ends_the_same_way() throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");

    rotate("k2-" + originalKeyId, ROTATED);

    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));
    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));
  }

  /** The way back an integrator would try first: put the old secret back, or assert the pair same
   *  (there is no resolved partner to assert against). Neither moves a row. */
  @Test
  void neither_restoring_the_old_secret_nor_asserting_a_pair_brings_the_rows_back() throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");
    rotate("k2-" + originalKeyId, ROTATED);
    pass.runReplay(tenant, "HmacRot1915");

    resolver.assertSame(tenant, ENTITY_TYPE,
        resolver.identityRefOf(tenant, ENTITY_TYPE, ERP, "e1").orElseThrow(),
        resolver.identityRefOf(tenant, ENTITY_TYPE, CRM, "m1").orElseThrow(), "it", "same customer");
    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));

    rotate(originalKeyId, originalSecret);
    assertEverySixRowsAmbiguousAndUnlinked(tenant, pass.runReplay(tenant, "HmacRot1915"));
  }

  /** The one way back: the old secret returns BEFORE any replay ran under the new one. The stored
   *  fingerprints still match, so nothing moved. */
  @Test
  void restoring_the_old_secret_before_any_replay_leaves_every_row_resolved() throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");
    Map<String, String> before = crosswalk(tenant);

    rotate(originalKeyId, ROTATED);
    rotate(originalKeyId, originalSecret);

    EntityResolutionPass.PassSummary after = pass.runReplay(tenant, "HmacRot1915");
    assertThat(after.resolved()).isEqualTo(6);
    assertThat(crosswalk(tenant)).isEqualTo(before);
  }

  /** The boundary of the contract: a pair asserted {@code same} skips the cascade (the assertion
   *  outranks every rule), so it does not depend on the fingerprints. After a rotation those two
   *  rows stay resolved, in the same entity; the four that rest on the exact rule turn ambiguous. */
  @Test
  void a_pair_asserted_same_stays_resolved_while_the_exact_rule_rows_turn_ambiguous() throws Exception {
    long tenant = NEXT_TENANT.getAndIncrement();
    seed(tenant);
    pass.runReplay(tenant, "HmacRot1915");
    resolver.assertSame(tenant, ENTITY_TYPE,
        resolver.identityRefOf(tenant, ENTITY_TYPE, ERP, "e1").orElseThrow(),
        resolver.identityRefOf(tenant, ENTITY_TYPE, CRM, "m1").orElseThrow(), "it", "same customer");
    Map<String, String> before = crosswalk(tenant);

    rotate(originalKeyId, ROTATED);
    EntityResolutionPass.PassSummary summary = pass.runReplay(tenant, "HmacRot1915");

    Map<String, String> after = crosswalk(tenant);
    assertThat(after.get("e1")).as("asserted: " + after).isEqualTo(before.get("e1"));
    assertThat(after.get("m1")).isEqualTo(before.get("m1"));
    assertThat(countState(after, "resolved")).isEqualTo(2);
    assertThat(countState(after, "ambiguous")).isEqualTo(4);
    assertThat(summary.resolved()).isEqualTo(2);
  }
}
