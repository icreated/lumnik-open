package io.lumnik.hub.connector.api.types;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public sealed interface ResolvedCredential {
  // toString() is overridden on every variant so a credential can never leak its secret fields
  // through a log line or an exception message. Non-sensitive fields (username, expiresAt) are kept
  // to preserve debuggability.
  record ApiKey(String key) implements ResolvedCredential {
    @Override public String toString() { return "ApiKey[key=***]"; }
  }
  @SuppressWarnings("java:S2068") // 'password' is a field name; toString masks the value
  record Basic(String username, String password) implements ResolvedCredential {
    @Override public String toString() { return "Basic[username=" + username + ", password=***]"; }
  }
  record OAuth2(String accessToken, Instant expiresAt, String refreshToken) implements ResolvedCredential {
    @Override public String toString() {
      return "OAuth2[accessToken=***, expiresAt=" + expiresAt + ", refreshToken=***]";
    }
  }

  @SuppressWarnings("java:S2068") // 'passphrase' is a field name, not a hardcoded secret
  record SshKey(byte[] privateKey, String passphrase) implements ResolvedCredential {
    @Override public boolean equals(Object o) {
      return o instanceof SshKey(byte[] otherKey, String otherPass)
          && Arrays.equals(privateKey, otherKey)
          && Objects.equals(passphrase, otherPass);
    }
    @Override public int hashCode() { return 31 * Arrays.hashCode(privateKey) + Objects.hashCode(passphrase); }
    @Override public String toString() { return "SshKey[privateKey=***, passphrase=***]"; }
  }

  record Certificate(byte[] cert, byte[] privateKey) implements ResolvedCredential {
    @Override public boolean equals(Object o) {
      return o instanceof Certificate(byte[] otherCert, byte[] otherKey)
          && Arrays.equals(cert, otherCert)
          && Arrays.equals(privateKey, otherKey);
    }
    @Override public int hashCode() { return 31 * Arrays.hashCode(cert) + Arrays.hashCode(privateKey); }
    @Override public String toString() { return "Certificate[cert=***, privateKey=***]"; }
  }
}
