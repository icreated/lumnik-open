package io.lumnik.hub.webhook;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SecretCipherIT {

    @Inject SecretCipher cipher;
    @Inject EntityManager em;

    @BeforeEach void setTenant() { CurrentTenantHolder.set(1L); }
    @AfterEach  void clear()     { CurrentTenantHolder.clear(); }

    @Test
    void encrypt_then_decrypt_returns_original() {
        byte[] enc = cipher.encrypt("hello-secret");
        assertThat(enc).isNotNull();
        assertThat(cipher.decrypt(enc)).isEqualTo("hello-secret");
    }

    @Test
    void ciphertext_declares_aes256_in_its_pgp_header() {
        // The doc (security/rls.md) promises AES-256 for connector/webhook credentials — lock the
        // algorithm to the promise. OpenPGP Symmetric-Key ESK packet: tag octet, one-octet length,
        // version 4, then the symmetric-cipher octet: 7 = AES-128 (pgcrypto's default), 9 = AES-256.
        byte[] enc = cipher.encrypt("algo-probe");
        assertThat(enc[2]).as("SKESK packet version").isEqualTo((byte) 4);
        assertThat(enc[3]).as("cipher algo octet (9 = AES-256, 7 = AES-128)").isEqualTo((byte) 9);
    }

    @Test
    void legacy_aes128_ciphertext_still_decrypts() {
        // Ciphertexts written before the aes256 switch carry their algo in the PGP header —
        // pgp_sym_decrypt honours it, so old rows must keep decrypting unchanged.
        String key = ConfigProvider.getConfig()
            .getValue("lumnik.crypto.secret-key", String.class);
        byte[] legacy = (byte[]) em.createNativeQuery(
                "SELECT public.pgp_sym_encrypt(cast(? as text), cast(? as text), 'cipher-algo=aes128')")
            .setParameter(1, "legacy-secret")
            .setParameter(2, key)
            .getSingleResult();
        assertThat(cipher.decrypt(legacy)).isEqualTo("legacy-secret");
    }

    @Test
    void encrypt_null_returns_null() {
        assertThat(cipher.encrypt(null)).isNull();
    }

    @Test
    void decrypt_null_returns_null() {
        assertThat(cipher.decrypt(null)).isNull();
    }
}
