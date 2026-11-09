package io.lumnik.hub.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The bound on {@code core.event_outbox.entity_key} and {@code dedup_key}: both are VARCHAR(200) (V24), while a
 * decision's key can be raw text from an arbitrary customer column with no bound at all.
 */
public final class OutboxKey {

    static final int MAX = 200;

    private OutboxKey() {}

    /** Readable while it fits; beyond that a stable digest of the whole value — still deterministic, so
     *  ON CONFLICT still dedupes, and the row is emitted instead of a 22001 killing the decision.
     *  The payload keeps the real key verbatim (jsonb is unbounded): the operator must be able to
     *  see which document this is. Measured in UTF-16 units, i.e. conservatively. */
    public static String bounded(String s) {
        if (s == null || s.length() <= MAX) return s;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
        }
    }
}
