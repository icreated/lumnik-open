package io.lumnik.hub.tenant;

import io.lumnik.common.security.DefaultScopeSource;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * The hub's answer to {@link DefaultScopeSource}: the bound user's {@code default_scope}.
 *
 * <p>Transactional for the reason {@link UserLoginGuard} explains (the RLS tenant GUC is
 * transaction-scoped); {@code UserDefaultScopeSourceRlsIT} pins it. An id with no visible row —
 * a foreign IdP, or another tenant's user under RLS — reads as empty: hiding must never surface
 * as someone else's preference.
 */
@ApplicationScoped
public class UserDefaultScopeSource implements DefaultScopeSource {

    @Inject UserRepository users;

    @Override
    @Transactional
    public Optional<String> forCurrentUser() {
        Long id = TenantContextFilter.currentUser();
        if (id == null) return Optional.empty();
        return users.findByIdOptional(id)
            .map(User::getDefaultScope)
            .filter(s -> !s.isBlank());
    }
}
