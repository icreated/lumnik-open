package io.lumnik.identity.user;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

    /**
     * One user of one tenant by id, or {@code null} — the tenant IN THE QUERY. SCIM's read
     * ({@code ScimUserService.get}) needs the predicate but throws {@code ScimErrors.notFound},
     * not the plain 404 {@code Require.owned} raises, so it resolves through here rather than there.
     */
    public User findForTenant(Long id, Long tenantId) {
        return find("id = ?1 AND tenantId = ?2", id, tenantId).firstResult();
    }

    public List<User> listAll() {
        return list("archivedAt IS NULL AND deletedAt IS NULL ORDER BY name");
    }

    /** Resolve by login restricted to a given IdP issuer, EXCLUDING soft-deleted rows (archived rows
     *  stay discoverable for re-provision). SCIM ops must not reach a local user (idpIssuer NULL) or
     *  another issuer's user within the same tenant; DELETE removes the resource from listings. */
    public Optional<User> findByLoginAndIssuerNotDeleted(String login, String issuer) {
        return find("login = ?1 AND idpIssuer = ?2 AND deletedAt IS NULL", login, issuer).firstResultOptional();
    }

    /** Resolve an external identity EXCLUDING soft-deleted rows (archived included) — for SCIM
     *  filters, where DELETE removes the resource but a deactivated user stays discoverable. */
    public Optional<User> findByExternalIdentityNotDeleted(String issuer, String subject) {
        return find("idpIssuer = ?1 AND idpSubject = ?2 AND deletedAt IS NULL", issuer, subject).firstResultOptional();
    }

    public boolean loginExists(String login) {
        return count("login = ?1", login) > 0;
    }

    /** True iff some user in this tenant already holds {@code login} (ANY lifecycle state — the
     *  {@code (tenant_id, login)} unique constraint counts archived/soft-deleted rows too).
     *  Tenant-scoped explicitly so it is correct whether or not RLS is enforcing on the connection. */
    public boolean loginTakenInTenant(String login, Long tenantId) {
        return count("login = ?1 AND tenantId = ?2", login, tenantId) > 0;
    }

    public Optional<User> findByExternalIdentity(String issuer, String subject) {
        return find("idpIssuer = ?1 AND idpSubject = ?2 AND archivedAt IS NULL AND deletedAt IS NULL",
                    issuer, subject).firstResultOptional();
    }

    /** Resolve an external identity in ANY lifecycle state (archived/deleted included) — the login
     *  path must SEE a deactivated row to reject it, not filter it out and recreate it. */
    public Optional<User> findByExternalIdentityAnyState(String issuer, String subject) {
        return find("idpIssuer = ?1 AND idpSubject = ?2", issuer, subject).firstResultOptional();
    }
}
