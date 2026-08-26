package io.lumnik.hub.connector.credentials;

import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.exceptions.CredentialNotFoundException;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.webhook.SecretCipher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class CredentialResolverImpl implements CredentialResolver {

    @Inject ConnectorCredentialRepository repo;
    @Inject SecretCipher cipher;

    @Override
    public ResolvedCredential resolve(UUID credentialId) {
        ConnectorCredential c = repo.findById(credentialId);
        if (c == null) {
            throw new CredentialNotFoundException("credential not found: " + credentialId);
        }
        String plaintext = cipher.decrypt(c.getCiphertext());
        return switch (c.getKind()) {
            case "ApiKey" -> new ResolvedCredential.ApiKey(plaintext);
            case "Basic" -> {
                int idx = plaintext.indexOf(':');
                if (idx < 0) throw new CredentialNotFoundException("Malformed Basic secret for: " + credentialId);
                yield new ResolvedCredential.Basic(plaintext.substring(0, idx), plaintext.substring(idx + 1));
            }
            default -> throw new CredentialNotFoundException("Unsupported credential kind: " + c.getKind());
        };
    }
}
