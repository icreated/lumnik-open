package io.lumnik.hub.entity.resolution;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot #616 of the #613 entity-resolution arc (design doc §6): the fingerprint is an HMAC,
 * domain-separated over at least tenant, entity type and rule id, so one value never
 * fingerprints identically across two tenants — and it is deterministic for a fixed tenant, rule
 * and normalized value, so a replay looks up the same row it wrote.
 */
@QuarkusTest
class EntityFingerprintServiceTest {

  @Inject EntityFingerprintService service;

  private static final List<String> EMAIL_FIELD = List.of("email");
  private static final List<String> NORMALIZE = List.of("clean", "lower");

  @Test
  void the_same_value_fingerprints_differently_under_two_tenants() {
    Map<String, String> values = Map.of("email", "alice@example.com");

    Optional<byte[]> tenantA = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, values);
    Optional<byte[]> tenantB = service.fingerprint(9002, "customer", "email", EMAIL_FIELD, NORMALIZE, values);

    assertThat(tenantA.get()).isNotEqualTo(tenantB.get());
  }

  @Test
  void the_same_tenant_rule_and_value_fingerprints_deterministically() {
    Map<String, String> values = Map.of("email", "alice@example.com");

    Optional<byte[]> a = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, values);
    Optional<byte[]> b = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, values);

    assertThat(a.get()).isEqualTo(b.get());
  }

  @Test
  void a_different_rule_id_fingerprints_differently_for_the_same_tenant_and_value() {
    Map<String, String> values = Map.of("email", "alice@example.com");

    Optional<byte[]> ruleA = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, values);
    Optional<byte[]> ruleB = service.fingerprint(9001, "customer", "other-rule", EMAIL_FIELD, NORMALIZE, values);

    assertThat(ruleA.get()).isNotEqualTo(ruleB.get());
  }

  @Test
  void a_composite_rules_fields_do_not_collide_across_a_boundary_shift() {
    // Both sides fully non-blank on purpose (unlike the design doc's own "Martin"/"Jean" vs
    // "Martin Jean"/"" example, LengthPrefixedEncodingTest pins that one at the encoding layer):
    // this test is about the boundary shift alone, not the blank-field refusal below.
    List<String> fields = List.of("last_name", "first_name");

    Optional<byte[]> ab_c = service.fingerprint(9001, "customer", "civil-identity", fields, List.of(),
        Map.of("last_name", "ab", "first_name", "c"));
    Optional<byte[]> a_bc = service.fingerprint(9001, "customer", "civil-identity", fields, List.of(),
        Map.of("last_name", "a", "first_name", "bc"));

    assertThat(ab_c.get()).isNotEqualTo(a_bc.get());
  }

  @Test
  void ruleIdentity_folds_in_the_configured_hmac_key_id() {
    RuleIdentity id = service.ruleIdentity("email", EMAIL_FIELD, NORMALIZE);

    assertThat(id.ruleId()).isEqualTo("email");
    assertThat(id.hmacKeyId()).isNotBlank();
  }

  @Test
  void a_row_missing_a_declared_field_yields_no_fingerprint() {
    Optional<byte[]> fp = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, Map.of());

    assertThat(fp).as("email is absent from the row — this rule cannot key on it").isEmpty();
  }

  @Test
  void two_unrelated_rows_both_missing_the_same_field_cannot_be_matched_on_that_rule() {
    // Copilot round 1 on PR #633: rawFieldValues.get(field) returning null for a missing field
    // used to flow straight into the HMAC input, so every row missing `email` fingerprinted
    // identically — a false merge manufactured by treating "absent" as just another value.
    Optional<byte[]> rowA = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, Map.of());
    Optional<byte[]> rowB = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE, Map.of());

    assertThat(rowA).isEmpty();
    assertThat(rowB).isEmpty();
  }

  @Test
  void a_composite_rule_with_only_one_of_two_fields_present_yields_no_fingerprint() {
    List<String> fields = List.of("last_name", "first_name");

    Optional<byte[]> fp = service.fingerprint(9001, "customer", "civil-identity", fields, List.of(),
        Map.of("last_name", "Martin"));

    assertThat(fp).as("a partial composite does not identify a row").isEmpty();
  }

  @Test
  void a_blank_field_value_yields_no_fingerprint() {
    // Copilot round 2 on PR #633: round 1 only checked for a missing (null) field. A field that
    // is PRESENT but empty passed straight through — two unrelated rows with email="" would have
    // collided on the same fingerprint, just like two rows missing email entirely.
    Optional<byte[]> fp = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE,
        Map.of("email", ""));

    assertThat(fp).as("an empty value carries no identity data").isEmpty();
  }

  @Test
  void a_whitespace_only_field_value_yields_no_fingerprint() {
    Optional<byte[]> fp = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE,
        Map.of("email", "   "));

    assertThat(fp).as("whitespace-only carries no identity data").isEmpty();
  }

  @Test
  void two_unrelated_rows_with_a_blank_field_cannot_be_matched_on_that_rule() {
    Optional<byte[]> rowA = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE,
        Map.of("email", ""));
    Optional<byte[]> rowB = service.fingerprint(9001, "customer", "email", EMAIL_FIELD, NORMALIZE,
        Map.of("email", "  "));

    assertThat(rowA).isEmpty();
    assertThat(rowB).isEmpty();
  }

  @Test
  void a_composite_rule_with_one_blank_field_yields_no_fingerprint() {
    List<String> fields = List.of("last_name", "first_name");

    Optional<byte[]> fp = service.fingerprint(9001, "customer", "civil-identity", fields, List.of(),
        Map.of("last_name", "Martin", "first_name", ""));

    assertThat(fp).as("a blank field in a composite rule is the same refusal as a missing one").isEmpty();
  }
}
