package io.lumnik.hub.entity.resolution;

import io.lumnik.common.text.Text;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * The active HMAC key used to fingerprint resolution rule keys (design doc §6) — an id (folded
 * into {@link RuleIdentity}, never the secret) plus the secret itself. One active key, not a
 * keyring of past ones: the secret must stay stable for the life of the resolution data. Changing it
 * invalidates every stored fingerprint, and the next replay does NOT re-link the rows resolved by an
 * {@code exact:} rule — each one meets the guard against recycling a resolved link and ends ambiguous
 * (#1915). A row held by an active {@code same} assertion keeps its link (the cascade is skipped).
 * Only restoring the old secret BEFORE that replay undoes it. A true rotation needs an explicit
 * rekey that recomputes the fingerprints and keeps the identities; it is never a side effect of a replay.
 *
 * <p>{@code macKey()} is built once at startup, not per fingerprint call: {@code SecretKeySpec} is
 * immutable and thread-safe to share, and the config value never changes while the app runs.
 *
 * <p><b>{@code @Startup} forces this validation to run at boot</b>, same posture as {@code
 * io.lumnik.hub.secret.MasterKeyValidator} — without it, this bean initializes lazily on the first
 * fingerprint call, so a production deployment that forgot to set
 * {@code LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET} would already be running, and storing predictable
 * HMACs of emails and other low-entropy identifiers, before the placeholder was ever noticed
 * (Copilot round 1 on PR #633).
 */
@Startup
@ApplicationScoped
public class FingerprintHmacKey {

  private static final String PLACEHOLDER = "CHANGE_ME_AT_DEPLOY_TIME_OR_STARTUP_WILL_FAIL";

  @ConfigProperty(name = "lumnik.entity.resolution.hmac.key-id")
  String keyId;

  @ConfigProperty(name = "lumnik.entity.resolution.hmac.secret")
  String secret;

  private SecretKeySpec macKey;

  @PostConstruct
  void init() {
    if (Text.blank(secret)) {
      throw new IllegalStateException(
          "lumnik.entity.resolution.hmac.secret is not set. Generate one with "
          + "`openssl rand -base64 32` and export LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET before "
          + "starting lumnik.");
    }
    if (PLACEHOLDER.equals(secret)) {
      throw new IllegalStateException(
          "lumnik.entity.resolution.hmac.secret is still the placeholder. Set a real secret via "
          + "env LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET.");
    }
    if (secret.length() < 16) {
      throw new IllegalStateException(
          "lumnik.entity.resolution.hmac.secret is too short (< 16 chars). Use "
          + "`openssl rand -base64 32`.");
    }
    macKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public String keyId() {
    return keyId;
  }

  public SecretKeySpec macKey() {
    return macKey;
  }
}
