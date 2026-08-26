package io.lumnik.hub.connector.api.helpers;

import io.lumnik.hub.connector.api.types.ResolvedCredential;
import java.util.UUID;

public interface CredentialResolver {
  ResolvedCredential resolve(UUID credentialId);
}
