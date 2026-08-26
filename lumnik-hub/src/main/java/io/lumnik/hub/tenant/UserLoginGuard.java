package io.lumnik.hub.tenant;

import io.lumnik.identity.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The per-request deactivation check behind {@link TenantContextFilter}'s user_id path.
 *
 * <p>The lookup MUST run inside a transaction: the RLS tenant GUC is applied with
 * {@code SET LOCAL} by {@code TenantConnectionInterceptor} on connection acquire, and
 * {@code SET LOCAL} is transaction-scoped — on a non-enlisted (autocommit) read it commits into
 * its own implicit transaction and is gone before the SELECT runs, leaving the GUC {@code ""}/NULL
 * (the 2026-08-11 readonly-datasource scar). Proven by {@code UserDeactivationGuardRlsIT}: without
 * this transaction the guard never fires on an RLS-enforced hub.
 */
@ApplicationScoped
public class UserLoginGuard {

    @Inject
    UserRepository users;

    /**
     * True iff the user row exists in the current tenant and may no longer authenticate
     * (archived, soft-deleted, or locked). An id with no visible row returns false — the hub
     * does not own every IdP's user table, and RLS hides other tenants' rows.
     */
    @Transactional
    public boolean isDeactivated(long userId) {
        return users.findByIdOptional(userId).map(u -> !u.isLoginActive()).orElse(false);
    }
}
