package io.lumnik.hub.secret;

import java.util.Optional;

/**
 * Protocol-agnostic entry point for resolving secrets by name within the current tenant context.
 * <p>
 * Callers must establish a tenant context (via {@code CurrentTenantHolder.set(tenantId)} or
 * {@code TenantContext.callAs(tenantId, ...)}) and a {@link SecretAudience} before invoking
 * {@link #resolve(String)}.
 */
public interface SecretResolver {

    /**
     * Resolve a secret value by name for the current tenant.
     *
     * @param name secret name (e.g. "STRIPE_API_KEY")
     * @return the decrypted value, or empty if no such secret exists for the current tenant
     * @throws SecretNotServedException when the secret exists but the audience does not carry
     *         every scope of it (#2015)
     */
    Optional<String> resolve(String name);
}
