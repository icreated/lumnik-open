package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

import java.security.PublicKey;

/**
 * The OpenSSH SHA-256 fingerprint of an SSH host key — the same string {@code ssh-keygen -lf}
 * prints, so what the hub reports and what the integrator's own tooling shows can be compared
 * character for character without anyone converting a format by hand.
 */
public final class HostKeyFingerprint {

    /** The OpenSSH prefix a SHA-256 fingerprint may carry, stripped before comparison. */
    private static final String SHA256_PREFIX = "SHA256:";

    private HostKeyFingerprint() {
    }

    public static String of(PublicKey key) {
        if (key == null) {
            throw new IllegalArgumentException("host key is null");
        }
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
    }

    /**
     * A declared fingerprint may arrive with or without its {@code SHA256:} prefix, in either
     * case, and with whitespace around it — every shape ssh-keygen, ssh-keyscan or a copy out of
     * a runbook might hand over. A blank declaration matches nothing: absence is never agreement.
     */
    public static boolean matches(String declared, PublicKey key) {
        if (declared == null || declared.isBlank()) {
            return false;
        }
        return normalise(declared).equals(normalise(of(key)));
    }

    private static String normalise(String fingerprint) {
        String value = fingerprint.trim();
        if (value.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())) {
            value = value.substring(SHA256_PREFIX.length());
        }
        while (value.endsWith("=")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
