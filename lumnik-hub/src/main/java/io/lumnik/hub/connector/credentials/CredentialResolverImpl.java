package io.lumnik.hub.connector.credentials;

import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.exceptions.CredentialNotFoundException;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.webhook.SecretCipher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class CredentialResolverImpl implements CredentialResolver {

    @Inject ConnectorCredentialRepository repo;
    @Inject SecretCipher cipher;

    /**
     * Makes sure there IS a transaction, rather than requiring one from its caller.
     *
     * <p>{@code connector.connector_credential} is RLS-enforced (policy {@code tenant_isolation},
     * V9, amended by V22 for the system sentinel), and the Agroal interceptor that issues
     * {@code SET LOCAL app.current_tenant} only fires for a JTA-enlisted connection. So a caller
     * with no transaction read a table it could not see: no row, and a
     * {@link CredentialNotFoundException} for a credential that exists. Every caller happened to
     * hold a transaction until one deliberately did not — {@code ConnectorType.runStart}, which
     * probes the source and must not keep a pooled connection while it does — and this is the form
     * that makes the precondition unnecessary rather than a rule to remember. See
     * {@code CredentialResolvedOutsideATransactionRlsIT}.
     *
     * <p>{@code joiningExisting}, deliberately, not {@code requiringNew}: the need is "a
     * transaction exists", never "a separate one". A new one SUSPENDS the caller's, so it cannot see
     * rows the caller has written and not yet committed — and {@code CredentialResolverIT} does
     * exactly that under {@code @TestTransaction}, persisting a credential and resolving it in the
     * same breath. requiringNew was the first attempt and it turned those two green tests red; the
     * gate caught it, which is what the gate is for.
     *
     * <p>The tenant context is NOT changed here, only the transaction. Forcing system mode inside a
     * shared resolver would widen what a request-path caller can see, which is not this method's
     * decision to make.
     */
    @Override
    public ResolvedCredential resolve(UUID credentialId) {
        return QuarkusTransaction.joiningExisting().call(() -> resolveInTx(credentialId));
    }

    private ResolvedCredential resolveInTx(UUID credentialId) {
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
