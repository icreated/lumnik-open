package io.lumnik.hub.view;

import io.lumnik.hub.entity.resolution.FingerprintHmacKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Seals and opens View page cursors with a key derived from the deployment's existing HMAC
 * secret — no new knob to set. The derivation is domain-separated ({@code HMAC(secret, label)}),
 * so the cursor key is not the fingerprint key itself. Rotating the secret makes every outstanding
 * cursor stale, which is the one refusal a cursor already has.
 */
@ApplicationScoped
public class ViewCursors {

    private static final String LABEL = "lumnik.view.cursor.v" + ViewPageContract.VERSION;

    @Inject FingerprintHmacKey hmac;

    private volatile byte[] key;

    public String seal(String binding, ViewCursor.Boundary boundary) {
        return ViewCursor.seal(key(), binding, boundary);
    }

    /** Refuses with {@link ViewCursor#REFUSAL} unless the token is exactly one issued for {@code binding}. */
    public ViewCursor.Boundary open(String binding, String token) {
        return ViewCursor.open(key(), binding, token);
    }

    private byte[] key() {
        byte[] k = key;
        if (k == null) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(hmac.macKey());
                k = mac.doFinal(LABEL.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("cannot derive the view cursor key", e);
            }
            key = k;
        }
        return k;
    }
}
