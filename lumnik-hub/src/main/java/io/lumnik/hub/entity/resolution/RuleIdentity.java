package io.lumnik.hub.entity.resolution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The effective identity of one cascade rule (design doc §6) — what a hash of the declared YAML
 * alone cannot see: a normalizer implementation change, an engine change, or an HMAC key rotation
 * would all leave a stale fingerprint looking valid, which is a silent false merge.
 *
 * <pre>
 * email@&lt;policy-hash&gt;@engine-v1@hmac-k2@clean:1.0.0@lower:1.0.0
 *         │              │         │        └─ normalizer semantic versions (allowlist-supplied)
 *         │              │         └─ HMAC key id (never the secret)
 *         │              └─ resolution engine version
 *         └─ declared rule content hash
 * </pre>
 */
public record RuleIdentity(
    String ruleId,
    String policyHash,
    String engineVersion,
    String hmacKeyId,
    List<String> normalizerVersions) {

  public static final String ENGINE_VERSION = "v1";

  /**
   * Builds the identity of a rule declared as {@code fields:} normalized through {@code
   * normalizeHookIds:} (in order). Throws {@link NormalizerNotAdmittedException} for any id not in
   * {@link NormalizerAllowlist#ADMITTED} — a rule identity cannot be minted for a hook that
   * carries no pinned version to fold in.
   */
  public static RuleIdentity of(
      String ruleId, List<String> fields, List<String> normalizeHookIds, String hmacKeyId) {
    List<String> versions = new ArrayList<>();
    for (String hookId : normalizeHookIds) {
      if (!NormalizerAllowlist.isAdmitted(hookId)) throw new NormalizerNotAdmittedException(hookId);
      versions.add(hookId + ":" + NormalizerAllowlist.versionOf(hookId));
    }
    return new RuleIdentity(ruleId, policyHash(fields, normalizeHookIds), ENGINE_VERSION,
        hmacKeyId, List.copyOf(versions));
  }

  /**
   * A hash of the rule's own declared shape — the fields it keys on and the chain it normalizes
   * them through — unambiguously encoded (a fields/normalize boundary shift must not collide, the
   * same discipline as {@link LengthPrefixedEncoding} already applies within one field list).
   */
  private static String policyHash(List<String> fields, List<String> normalizeHookIds) {
    List<String> tokens = new ArrayList<>();
    tokens.add(Integer.toString(fields.size()));
    tokens.addAll(fields);
    tokens.add(Integer.toString(normalizeHookIds.size()));
    tokens.addAll(normalizeHookIds);

    byte[] encoded = LengthPrefixedEncoding.encode(tokens);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
      return HexFormat.of().formatHex(digest, 0, 8); // 16 hex chars — a manifest edit marker, not a secret
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
    }
  }

  public String encode() {
    return ruleId + "@" + ruleVersion();
  }

  /**
   * Everything {@link #encode()} appends after the bare {@code ruleId} — the {@code rule_version}
   * column of {@code core.entity_identity_key} (design doc §3): a lookup matches {@code
   * rule_declared_id} and this value independently, so a fingerprint computed under a stale
   * version can never satisfy a current-version lookup.
   */
  public String ruleVersion() {
    StringBuilder sb = new StringBuilder();
    sb.append(policyHash).append("@engine-").append(engineVersion).append("@hmac-").append(hmacKeyId);
    for (String v : normalizerVersions) sb.append('@').append(v);
    return sb.toString();
  }
}
