package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface CredentialKind {
  record ApiKey()                                                      implements CredentialKind {}
  record Basic()                                                       implements CredentialKind {}
  record OAuth2(String authUrl, String tokenUrl, List<String> scopes) implements CredentialKind {}
  record SshKey()                                                      implements CredentialKind {}
  record Certificate(String format)                                    implements CredentialKind {}
}
