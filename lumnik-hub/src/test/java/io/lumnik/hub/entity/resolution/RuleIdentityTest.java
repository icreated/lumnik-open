package io.lumnik.hub.entity.resolution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot #616 of the #613 entity-resolution arc (design doc §6): the effective rule identity is
 * {@code <ruleId>@<policy-hash>@engine-<version>@hmac-<keyId>}, plus the normalizer versions the
 * allowlist supplies — a YAML-content hash alone cannot see a normalizer implementation change, an
 * engine change, or a key rotation. No CDI: pure value type / pure function.
 */
class RuleIdentityTest {

  @Test
  void a_change_of_normalizer_version_changes_the_rule_identity() {
    RuleIdentity a = new RuleIdentity("email", "abc123", "v1", "k1", List.of("lower:1.0.0"));
    RuleIdentity b = new RuleIdentity("email", "abc123", "v1", "k1", List.of("lower:1.1.0"));

    assertThat(a.encode()).isNotEqualTo(b.encode());
  }

  @Test
  void a_key_rotation_changes_the_rule_identity() {
    RuleIdentity a = new RuleIdentity("email", "abc123", "v1", "k1", List.of());
    RuleIdentity b = new RuleIdentity("email", "abc123", "v1", "k2", List.of());

    assertThat(a.encode()).isNotEqualTo(b.encode());
  }

  @Test
  void an_engine_version_bump_changes_the_rule_identity() {
    RuleIdentity a = new RuleIdentity("email", "abc123", "v1", "k1", List.of());
    RuleIdentity b = new RuleIdentity("email", "abc123", "v2", "k1", List.of());

    assertThat(a.encode()).isNotEqualTo(b.encode());
  }

  @Test
  void of_folds_rule_id_policy_hash_engine_and_hmac_key_into_the_identity() {
    RuleIdentity id = RuleIdentity.of("email", List.of("email"), List.of("clean", "lower"), "k2");

    assertThat(id.ruleId()).isEqualTo("email");
    assertThat(id.engineVersion()).isEqualTo(RuleIdentity.ENGINE_VERSION);
    assertThat(id.hmacKeyId()).isEqualTo("k2");
    assertThat(id.normalizerVersions()).containsExactly("clean:1.0.0", "lower:1.0.0");
    assertThat(id.encode())
        .startsWith("email@")
        .contains("@engine-" + RuleIdentity.ENGINE_VERSION)
        .contains("@hmac-k2")
        .contains("clean:1.0.0")
        .contains("lower:1.0.0");
  }

  @Test
  void of_changes_the_policy_hash_when_the_declared_fields_change() {
    RuleIdentity a = RuleIdentity.of("email", List.of("email"), List.of("clean"), "k1");
    RuleIdentity b = RuleIdentity.of("email", List.of("phone"), List.of("clean"), "k1");

    assertThat(a.policyHash()).isNotEqualTo(b.policyHash());
  }

  @Test
  void of_changes_the_policy_hash_when_the_normalize_chain_changes() {
    RuleIdentity a = RuleIdentity.of("email", List.of("email"), List.of("clean"), "k1");
    RuleIdentity b = RuleIdentity.of("email", List.of("email"), List.of("clean", "lower"), "k1");

    assertThat(a.policyHash()).isNotEqualTo(b.policyHash());
  }

  @Test
  void of_refuses_a_normalizer_outside_the_allowlist() {
    assertThatThrownBy(() -> RuleIdentity.of("email", List.of("email"), List.of("mask"), "k1"))
        .isInstanceOf(NormalizerNotAdmittedException.class);
  }

  /**
   * #617's crosswalk lookup index carries {@code rule_declared_id} and {@code rule_version} as
   * separate columns (design doc §3: keying on the full versioned identity would insert a new row
   * beside every stale one forever) — {@code ruleVersion()} is everything {@link #encode()} appends
   * after the bare {@code ruleId}, so a lookup can match declared id and version independently.
   */
  @Test
  void rule_version_is_the_encoded_identity_without_the_leading_rule_id() {
    RuleIdentity id = RuleIdentity.of("email", List.of("email"), List.of("clean", "lower"), "k2");

    assertThat(id.encode()).isEqualTo(id.ruleId() + "@" + id.ruleVersion());
  }
}
