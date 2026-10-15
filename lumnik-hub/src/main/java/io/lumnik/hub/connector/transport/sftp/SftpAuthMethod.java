package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.secret.SecretRefs;

import java.util.Map;

public sealed interface SftpAuthMethod permits SftpAuthMethod.Password, SftpAuthMethod.Key {
    // toString() masks the secret so a resolved auth method can't leak through a log or exception.
    record Password(String value) implements SftpAuthMethod {
        @Override public String toString() { return "Password[value=***]"; }
    }
    record Key(String pemContent) implements SftpAuthMethod {
        @Override public String toString() { return "Key[pemContent=***]"; }
    }

    /**
     * Resolves an SFTP transport's {@code key_env}/{@code password_env} config into an auth
     * method, exactly as both the reader-side and writer-side SFTP factories need it — shared so
     * the two copies can't drift, the way {@link SftpConnectionFactory} already shares the
     * connection bootstrap itself.
     */
    static SftpAuthMethod fromConfig(Map<String, Object> cfg, SecretRefs secrets) {
        String keyEnv = (String) cfg.get("key_env");
        String pwdEnv = (String) cfg.get("password_env");
        if (keyEnv != null) {
            return new Key(secrets.lookup(keyEnv));
        } else if (pwdEnv != null) {
            return new Password(secrets.lookup(pwdEnv));
        }
        return new Password(null);
    }
}
