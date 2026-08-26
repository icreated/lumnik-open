package io.lumnik.hub.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Encrypt/decrypt small secrets (HMAC keys, bearer tokens) via Postgres pgcrypto.
 * Round-trips through the DB so the encryption key never leaves the server process.
 */
@ApplicationScoped
public class SecretCipher {

    @Inject EntityManager em;

    @ConfigProperty(name = "lumnik.crypto.secret-key")
    private String key;

    @Transactional(Transactional.TxType.SUPPORTS)
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) return null;
        // aes256 explicitly — pgcrypto's default is AES-128, and security/rls.md promises AES-256
        // for this family too. Decrypt needs no option: the algo travels in the PGP header, so
        // pre-switch AES-128 ciphertexts keep decrypting (SecretCipherIT pins both facts).
        Object result = em.createNativeQuery("SELECT public.pgp_sym_encrypt(cast(? as text), cast(? as text), 'cipher-algo=aes256')")
            .setParameter(1, plaintext)
            .setParameter(2, key)
            .getSingleResult();
        return (byte[]) result;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null) return null;
        Object result = em.createNativeQuery("SELECT public.pgp_sym_decrypt(cast(? as bytea), cast(? as text))")
            .setParameter(1, ciphertext)
            .setParameter(2, key)
            .getSingleResult();
        return (String) result;
    }
}
