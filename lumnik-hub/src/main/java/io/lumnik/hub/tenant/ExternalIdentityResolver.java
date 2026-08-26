package io.lumnik.hub.tenant;

/**
 * The open seam for external-IdP identities (publication ritual, Act 1 — seam 2).
 * <p>
 * A JWT from our own realm carries a {@code user_id} claim and never needs this. A JWT from an
 * external IdP (BYO-Keycloak, enterprise SSO) carries only {@code (issuer, subject)} — resolving
 * that pair to a lumnik user (JIT provisioning) is an enterprise feature. The open hub declares
 * the seam; the implementation ships with the closed side (the pro {@code jit} package).
 * {@link TenantContextFilter} injects it as {@code Instance<>}: when no implementation is on the
 * classpath, a user_id-less JWT is refused with 401 — fail closed, never a user-less request.
 */
public interface ExternalIdentityResolver {

    /**
     * Resolve the external identity to a lumnik user id, creating the user on first login.
     *
     * @throws DeactivatedUserException when the identity exists but was deactivated
     *                                  (the filter turns this into a 403)
     */
    Long findOrCreate(String issuer, String subject, String name, String email);
}
