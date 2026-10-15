package io.lumnik.hub.entity.resolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Copilot round 1 on PR #633: the HMAC secret fell back to the public
 * {@code dev-only-key-do-not-use-in-prod-please-rotate} default in every profile — a production
 * deployment that forgot to set {@code LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET} still booted and
 * stored predictable HMACs of emails and other low-entropy identifiers. Same posture as {@code
 * io.lumnik.hub.secret.MasterKeyValidator}: {@code @Startup} forces this bean to initialize (and
 * therefore validate) before the app finishes booting, not lazily on the first fingerprint call.
 */
class FingerprintHmacKeyTest {

  @Test
  void rejects_missing_or_default_placeholder() {
    var key = new FingerprintHmacKey();
    key.secret = "CHANGE_ME_AT_DEPLOY_TIME_OR_STARTUP_WILL_FAIL";
    assertThrows(IllegalStateException.class, key::init);
  }

  @Test
  void rejects_empty_or_blank() {
    var key = new FingerprintHmacKey();
    key.secret = "";
    assertThrows(IllegalStateException.class, key::init);
    key.secret = "   ";
    assertThrows(IllegalStateException.class, key::init);
  }

  @Test
  void rejects_too_short() {
    var key = new FingerprintHmacKey();
    key.secret = "short";
    assertThrows(IllegalStateException.class, key::init);
  }

  @Test
  void accepts_a_valid_secret_and_builds_the_mac_key() {
    var key = new FingerprintHmacKey();
    key.keyId = "k1";
    key.secret = "this-is-a-valid-hmac-secret-of-sufficient-length";
    assertDoesNotThrow(key::init);
    assertThat(key.macKey()).isNotNull();
  }
}
