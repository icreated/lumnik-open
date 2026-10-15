package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V49 (#613/#615) — the entity-resolution crosswalk, fingerprint, assertion and evidence tables,
 * proved on the RLS-enforced harness (the only honest way to test RLS: the shared DevServices
 * container connects as the table owner, which skips RLS entirely).
 *
 * <p>Each test seeds through the {@code admin} datasource (owner, bypasses RLS — same idiom as
 * {@code UserDeactivationGuardRlsIT}) and reads/breaks things through the default {@code appDs}
 * (lumnik_app, RLS-enforced) with an explicit {@code SET LOCAL app.current_tenant} per the
 * production GUC contract.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityResolutionSchemaRlsIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject AgroalDataSource appDs;

  private static final String ENTITY_TYPE = "customer";

  private void asAdmin(SqlWork work) {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      work.run(st);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void asTenant(long tenant, SqlWork work) {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
      try {
        work.run(st);
        st.execute("COMMIT");
      } catch (Exception e) {
        // A pooled connection returned mid-transaction poisons whichever test draws it next
        // ("current transaction is aborted" on an otherwise unrelated statement) — roll back
        // before the try-with-resources close() returns it to the pool.
        try {
          st.execute("ROLLBACK");
        } catch (Exception ignored) {
          // best effort — the connection may already be unusable.
        }
        throw e;
      }
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private long countAsTenant(long tenant, String sql) {
    long[] result = {-1};
    asTenant(tenant, st -> {
      try (ResultSet rs = st.executeQuery(sql)) {
        rs.next();
        result[0] = rs.getLong(1);
      }
    });
    return result[0];
  }

  private void insertIdentity(long tenant, String source, String sourceId, UUID identityRef, String state, UUID entityId) {
    asAdmin(st -> st.execute("INSERT INTO core.entity_identity "
        + "(tenant_id, entity_type, source, source_id, identity_ref, state, entity_id) VALUES ("
        + tenant + ", '" + ENTITY_TYPE + "', '" + source + "', '" + sourceId + "', '" + identityRef + "', "
        + "'" + state + "', " + (entityId == null ? "NULL" : "'" + entityId + "'") + ")"));
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Statement st) throws SQLException;
  }

  // ---------------------------------------------------------------------------------------------
  // RLS tenant isolation, all four tables.
  // ---------------------------------------------------------------------------------------------

  @Test
  void entity_identity_is_tenant_isolated() {
    long t1 = 9101, t2 = 9102;
    insertIdentity(t1, "erp", "src-" + System.nanoTime(), UUID.randomUUID(), "unresolved_no_rule", null);
    insertIdentity(t2, "erp", "src-" + System.nanoTime(), UUID.randomUUID(), "unresolved_no_rule", null);

    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_identity WHERE tenant_id = " + t1))
        .isGreaterThanOrEqualTo(1);
    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_identity WHERE tenant_id = " + t2))
        .as("tenant 1 must not see tenant 2's crosswalk rows").isEqualTo(0);
  }

  @Test
  void entity_identity_key_is_tenant_isolated() {
    long t1 = 9111, t2 = 9112;
    String src1 = "src-" + System.nanoTime();
    String src2 = "src-" + System.nanoTime();
    insertIdentity(t1, "erp", src1, UUID.randomUUID(), "unresolved_no_rule", null);
    insertIdentity(t2, "erp", src2, UUID.randomUUID(), "unresolved_no_rule", null);
    asAdmin(st -> {
      st.execute(fingerprintInsert(t1, "erp", "email", "v1"));
      st.execute(fingerprintInsert(t2, "erp", "email", "v1"));
    });

    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_identity_key WHERE tenant_id = " + t1))
        .isEqualTo(1);
    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_identity_key WHERE tenant_id = " + t2))
        .as("tenant 1 must not see tenant 2's fingerprints").isEqualTo(0);
  }

  @Test
  void entity_assertion_is_tenant_isolated() {
    long t1 = 9121, t2 = 9122;
    UUID left1 = UUID.fromString("00000000-0000-0000-0000-000000009121");
    UUID right1 = UUID.fromString("00000000-0000-0000-0000-000000019121");
    insertIdentity(t1, "erp", "src-" + System.nanoTime(), left1, "unresolved_no_rule", null);
    insertIdentity(t1, "crm", "src-" + System.nanoTime(), right1, "unresolved_no_rule", null);
    asAdmin(st -> st.execute(assertionInsert(t1, left1, right1, "same")));

    UUID left2 = UUID.fromString("00000000-0000-0000-0000-000000009122");
    UUID right2 = UUID.fromString("00000000-0000-0000-0000-000000019122");
    insertIdentity(t2, "erp", "src-" + System.nanoTime(), left2, "unresolved_no_rule", null);
    insertIdentity(t2, "crm", "src-" + System.nanoTime(), right2, "unresolved_no_rule", null);
    asAdmin(st -> st.execute(assertionInsert(t2, left2, right2, "same")));

    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_assertion WHERE tenant_id = " + t1))
        .isEqualTo(1);
    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_assertion WHERE tenant_id = " + t2))
        .as("tenant 1 must not see tenant 2's assertions").isEqualTo(0);
  }

  @Test
  void entity_resolution_evidence_is_tenant_isolated() {
    long t1 = 9131, t2 = 9132;
    asAdmin(st -> {
      st.execute(evidenceInsert(t1, "erp", "src-" + System.nanoTime(), "unresolved_no_rule"));
      st.execute(evidenceInsert(t2, "erp", "src-" + System.nanoTime(), "unresolved_no_rule"));
    });

    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_resolution_evidence WHERE tenant_id = " + t1))
        .isEqualTo(1);
    assertThat(countAsTenant(t1, "SELECT count(*) FROM core.entity_resolution_evidence WHERE tenant_id = " + t2))
        .as("tenant 1 must not see tenant 2's evidence").isEqualTo(0);
  }

  // ---------------------------------------------------------------------------------------------
  // Shape constraints on core.entity_identity.
  // ---------------------------------------------------------------------------------------------

  @Test
  void resolved_state_requires_an_entity_id() {
    long t = 9201;
    assertThatThrownBy(() -> insertIdentity(t, "erp", "src-" + System.nanoTime(), UUID.randomUUID(), "resolved", null))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_identity_state_entity");
  }

  @Test
  void non_resolved_state_must_not_carry_an_entity_id() {
    long t = 9202;
    assertThatThrownBy(() -> insertIdentity(t, "erp", "src-" + System.nanoTime(), UUID.randomUUID(),
        "ambiguous", UUID.randomUUID()))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_identity_state_entity");
  }

  @Test
  void resolved_with_an_entity_id_is_accepted() {
    long t = 9203;
    insertIdentity(t, "erp", "src-" + System.nanoTime(), UUID.randomUUID(), "resolved", UUID.randomUUID());
    // no exception = green; the row is proved visible under RLS too.
    assertThat(countAsTenant(t, "SELECT count(*) FROM core.entity_identity WHERE tenant_id = " + t))
        .isEqualTo(1);
  }

  @Test
  void state_is_restricted_to_the_declared_domain() {
    long t = 9204;
    assertThatThrownBy(() -> insertIdentity(t, "erp", "src-" + System.nanoTime(), UUID.randomUUID(),
        "made_up_state", null))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_identity_state");
  }

  @Test
  void one_source_cannot_hold_two_identities_of_the_same_entity() {
    long t = 9205;
    UUID entity = UUID.randomUUID();
    insertIdentity(t, "erp", "src-a-" + System.nanoTime(), UUID.randomUUID(), "resolved", entity);
    String sourceIdB = "src-b-" + System.nanoTime();
    assertThatThrownBy(() -> insertIdentity(t, "erp", sourceIdB, UUID.randomUUID(), "resolved", entity))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("uq_entity_identity_cardinality");
  }

  @Test
  void two_ambiguous_rows_of_one_source_coexist_because_nulls_do_not_collide() {
    long t = 9206;
    String source = "erp";
    // Both ambiguous (entity_id IS NULL) — the non-partial UNIQUE(tenant, type, source, entity_id)
    // must not block this, which is the entire reason it is not a partial index.
    insertIdentity(t, source, "src-a-" + System.nanoTime(), UUID.randomUUID(), "ambiguous", null);
    insertIdentity(t, source, "src-b-" + System.nanoTime(), UUID.randomUUID(), "ambiguous", null);

    assertThat(countAsTenant(t, "SELECT count(*) FROM core.entity_identity "
        + "WHERE tenant_id = " + t + " AND state = 'ambiguous' AND entity_id IS NULL"))
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------------------------
  // core.entity_identity_key.
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_declared_rule_holds_at_most_one_fingerprint_per_identity() {
    long t = 9211;
    String source = "erp";
    String sourceId = "src-" + System.nanoTime();
    insertIdentity(t, source, sourceId, UUID.randomUUID(), "unresolved_no_rule", null);
    asAdmin(st -> st.execute(fingerprintInsert(t, source, "email", "v1")));

    // A second plain INSERT for the SAME declared rule (even under a different rule_version) must
    // collide on the PK — the key stops at the declared rule id, rule_version is a plain column an
    // upsert would replace, never a second physical row.
    assertThatThrownBy(() -> asAdmin(st -> st.execute(fingerprintInsert(t, source, "email", "v2"))))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("pk_entity_identity_key");
  }

  @Test
  void deleting_the_identity_cascades_its_fingerprints() {
    long t = 9212;
    String source = "erp";
    String sourceId = "src-" + System.nanoTime();
    insertIdentity(t, source, sourceId, UUID.randomUUID(), "unresolved_no_rule", null);
    asAdmin(st -> st.execute(fingerprintInsert(t, source, "email", "v1")));

    assertThat(countAsTenant(t, "SELECT count(*) FROM core.entity_identity_key WHERE tenant_id = " + t))
        .as("sanity: the fingerprint was actually created before we delete its parent").isEqualTo(1);

    asAdmin(st -> st.execute("DELETE FROM core.entity_identity WHERE tenant_id = " + t
        + " AND entity_type = '" + ENTITY_TYPE + "' AND source = '" + source + "' AND source_id = '" + sourceId + "'"));

    assertThat(countAsTenant(t, "SELECT count(*) FROM core.entity_identity_key WHERE tenant_id = " + t))
        .as("a fingerprint is derived data — it must not outlive its identity").isEqualTo(0);
  }

  // ---------------------------------------------------------------------------------------------
  // core.entity_assertion.
  // ---------------------------------------------------------------------------------------------

  @Test
  void an_assertion_requires_the_canonical_left_less_than_right_order() {
    long t = 9221;
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    insertIdentity(t, "erp", "src-a-" + System.nanoTime(), a, "unresolved_no_rule", null);
    insertIdentity(t, "crm", "src-b-" + System.nanoTime(), b, "unresolved_no_rule", null);

    // b > a, so declaring (right=b, left=a) is correct; the reverse must be refused.
    assertThatThrownBy(() -> asAdmin(st -> st.execute(assertionInsert(t, b, a, "same"))))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_assertion_order");
  }

  @Test
  void an_assertion_verdict_is_restricted_to_same_or_distinct() {
    long t = 9222;
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000ca");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000cb");
    insertIdentity(t, "erp", "src-a-" + System.nanoTime(), a, "unresolved_no_rule", null);
    insertIdentity(t, "crm", "src-b-" + System.nanoTime(), b, "unresolved_no_rule", null);

    assertThatThrownBy(() -> asAdmin(st -> st.execute(assertionInsert(t, a, b, "maybe"))))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_assertion_verdict");
  }

  @Test
  void an_assertion_cannot_reference_an_identity_of_another_tenant() {
    long ownTenant = 9231, otherTenant = 9232;
    UUID left = UUID.fromString("00000000-0000-0000-0000-0000000000da");
    UUID rightOfOtherTenant = UUID.fromString("00000000-0000-0000-0000-0000000000db");
    insertIdentity(ownTenant, "erp", "src-a-" + System.nanoTime(), left, "unresolved_no_rule", null);
    insertIdentity(otherTenant, "crm", "src-b-" + System.nanoTime(), rightOfOtherTenant, "unresolved_no_rule", null);

    // Declares tenant_id = ownTenant but the right ref only exists under otherTenant: the
    // three-column FK (identity_ref, tenant_id, entity_type) finds no matching row, so this is
    // structurally impossible, not merely blocked by application logic.
    assertThatThrownBy(() -> asAdmin(st -> st.execute(assertionInsert(ownTenant, left, rightOfOtherTenant, "same"))))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("fk_entity_assertion_right");
  }

  @Test
  void an_assertion_survives_deletion_of_a_source_row_by_construction() {
    long t = 9241;
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000ea");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000eb");
    insertIdentity(t, "erp", "src-a-" + System.nanoTime(), a, "unresolved_no_rule", null);
    insertIdentity(t, "crm", "src-b-" + System.nanoTime(), b, "unresolved_no_rule", null);
    asAdmin(st -> st.execute(assertionInsert(t, a, b, "distinct")));

    // ON DELETE RESTRICT: a human correction must not evaporate because a run cleaned a source row.
    assertThatThrownBy(() -> asAdmin(st -> st.execute("DELETE FROM core.entity_identity "
        + "WHERE tenant_id = " + t + " AND identity_ref = '" + a + "'")))
        .hasCauseInstanceOf(PSQLException.class);

    assertThat(countAsTenant(t, "SELECT count(*) FROM core.entity_assertion WHERE tenant_id = " + t))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // core.entity_resolution_evidence.
  // ---------------------------------------------------------------------------------------------

  @Test
  void evidence_outcome_is_restricted_to_the_declared_domain() {
    long t = 9251;
    assertThatThrownBy(() -> asAdmin(st -> st.execute(evidenceInsert(t, "erp", "src-" + System.nanoTime(), "made_up"))))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("ck_entity_resolution_evidence_outcome");
  }

  @Test
  void evidence_is_append_only_for_the_request_path_role() {
    long t = 9252;
    asAdmin(st -> st.execute(evidenceInsert(t, "erp", "src-" + System.nanoTime(), "unresolved_no_rule")));

    assertThatThrownBy(() -> asTenant(t, st -> st.execute(
        "UPDATE core.entity_resolution_evidence SET outcome = 'resolved' WHERE tenant_id = " + t)))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("permission denied");

    assertThatThrownBy(() -> asTenant(t, st -> st.execute(
        "DELETE FROM core.entity_resolution_evidence WHERE tenant_id = " + t)))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("permission denied");
  }

  /**
   * Copilot round 1 on PR #632: a single-column FK on {@code identity_ref} alone only proves SOME
   * identity somewhere carries that ref — it says nothing about whether it belongs to the SAME
   * tenant/entity_type this row itself declares. Without the composite FK, a row could name
   * {@code ownTenant} in {@code tenant_id} while {@code identity_ref} actually belongs to
   * {@code otherTenant}, which both misrepresents the ledger and turns the FK into a cross-tenant
   * existence oracle.
   */
  @Test
  void evidence_cannot_reference_an_identity_of_another_tenant() {
    long ownTenant = 9254, otherTenant = 9255;
    UUID refOfOtherTenant = UUID.randomUUID();
    insertIdentity(otherTenant, "erp", "src-" + System.nanoTime(), refOfOtherTenant, "unresolved_no_rule", null);

    assertThatThrownBy(() -> asAdmin(st -> st.execute("INSERT INTO core.entity_resolution_evidence "
        + "(tenant_id, entity_type, source, source_id, identity_ref, outcome, decided_by) VALUES ("
        + ownTenant + ", '" + ENTITY_TYPE + "', 'erp', 'src-" + System.nanoTime() + "', '"
        + refOfOtherTenant + "', 'resolved', 'automatic')")))
        .hasCauseInstanceOf(PSQLException.class)
        .hasMessageContaining("fk_entity_resolution_evidence_identity");
  }

  @Test
  void evidence_survives_deletion_of_its_identity_with_the_link_cleared() {
    long t = 9253;
    String source = "erp";
    String sourceId = "src-" + System.nanoTime();
    UUID ref = UUID.randomUUID();
    insertIdentity(t, source, sourceId, ref, "unresolved_no_rule", null);
    asAdmin(st -> st.execute("INSERT INTO core.entity_resolution_evidence "
        + "(tenant_id, entity_type, source, source_id, identity_ref, outcome, decided_by) VALUES ("
        + t + ", '" + ENTITY_TYPE + "', '" + source + "', '" + sourceId + "', '" + ref + "', "
        + "'unresolved_no_rule', 'automatic')"));

    asAdmin(st -> st.execute("DELETE FROM core.entity_identity WHERE tenant_id = " + t
        + " AND identity_ref = '" + ref + "'"));

    long[] identityRefIsNull = {-1};
    asTenant(t, st -> {
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM core.entity_resolution_evidence "
          + "WHERE tenant_id = " + t + " AND identity_ref IS NULL")) {
        rs.next();
        identityRefIsNull[0] = rs.getLong(1);
      }
    });
    assertThat(identityRefIsNull[0]).as("the ledger row survives, its link merely cleared").isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // SQL builders.
  // ---------------------------------------------------------------------------------------------

  private String fingerprintInsert(long tenant, String source, String ruleDeclaredId, String ruleVersion) {
    return "INSERT INTO core.entity_identity_key "
        + "(tenant_id, entity_type, source, source_id, rule_declared_id, rule_version, key_fingerprint) "
        + "SELECT tenant_id, entity_type, source, source_id, '" + ruleDeclaredId + "', '" + ruleVersion + "', "
        + "'\\xdeadbeef' FROM core.entity_identity "
        + "WHERE tenant_id = " + tenant + " AND source = '" + source + "'";
  }

  private String assertionInsert(long tenant, UUID left, UUID right, String verdict) {
    return "INSERT INTO core.entity_assertion "
        + "(tenant_id, entity_type, left_identity_ref, right_identity_ref, verdict, asserted_by) VALUES ("
        + tenant + ", '" + ENTITY_TYPE + "', '" + left + "', '" + right + "', '" + verdict + "', 'it')";
  }

  private String evidenceInsert(long tenant, String source, String sourceId, String outcome) {
    return "INSERT INTO core.entity_resolution_evidence "
        + "(tenant_id, entity_type, source, source_id, outcome, decided_by) VALUES ("
        + tenant + ", '" + ENTITY_TYPE + "', '" + source + "', '" + sourceId + "', '" + outcome + "', 'automatic')";
  }
}
