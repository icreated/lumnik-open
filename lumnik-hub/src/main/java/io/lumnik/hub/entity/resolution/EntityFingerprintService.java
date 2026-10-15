package io.lumnik.hub.entity.resolution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ties normalization, rule identity and the HMAC together into the one call the cascade engine
 * (#617+) needs per rule per row: normalize each declared field, then fingerprint the result
 * (design doc §6/§7). The HMAC is domain-separated over tenant, entity type and rule id, so one
 * raw value never fingerprints identically across two tenants or two rules.
 */
@ApplicationScoped
public class EntityFingerprintService {

  @Inject NormalizationOrchestrator orchestrator;
  @Inject FingerprintHmacKey hmacKey;

  /** The effective identity of a rule keyed on {@code fields}, normalized through {@code
   * normalizeHookIds} — the {@code rule_version} an index lookup must match exactly. */
  public RuleIdentity ruleIdentity(String ruleId, List<String> fields, List<String> normalizeHookIds) {
    return RuleIdentity.of(ruleId, fields, normalizeHookIds, hmacKey.keyId());
  }

  /**
   * Normalizes each of {@code fields} from {@code rawFieldValues} (in declared order) through
   * {@code normalizeHookIds}, then computes the HMAC over tenant, entity type, rule id and the
   * normalized values, each length-prefixed so a composite rule's field boundary can never shift
   * without changing the fingerprint (design doc §6).
   *
   * <p><b>Composite-field policy: every declared field must carry a real, non-blank value, or
   * there is no fingerprint.</b> A rule cannot key on a row missing one of the fields it declares,
   * or on a field that is present but empty or whitespace-only — the design requires {@code
   * unresolved_no_rule} for exactly that row, not a key. Encoding a missing or blank field as just
   * another value would make every row lacking real data for that field fingerprint identically —
   * a false merge manufactured by the fingerprint function itself, the same failure shape §6
   * already warns encoding ambiguity causes. Returns {@link Optional#empty()} for that row/rule
   * instead (Copilot rounds 1 and 2 on PR #633).
   */
  public Optional<byte[]> fingerprint(
      long tenantId,
      String entityType,
      String ruleId,
      List<String> fields,
      List<String> normalizeHookIds,
      Map<String, String> rawFieldValues) {
    List<String> domain = new ArrayList<>();
    domain.add(Long.toString(tenantId));
    domain.add(entityType);
    domain.add(ruleId);
    for (String field : fields) {
      String normalized = orchestrator.normalize(normalizeHookIds, field, rawFieldValues.get(field));
      if (normalized == null || normalized.isBlank()) return Optional.empty();
      domain.add(normalized);
    }

    byte[] encoded = LengthPrefixedEncoding.encode(domain);
    try {
      // A fresh Mac per call: Mac is not thread-safe, so it cannot be cached on this
      // @ApplicationScoped bean. The key itself is cached — see FingerprintHmacKey.macKey().
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey.macKey());
      return Optional.of(mac.doFinal(encoded));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC fingerprint computation failed", e);
    }
  }
}
