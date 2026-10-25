package io.lumnik.identity.role;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RoleRepository implements PanacheRepository<Role> {

    public List<Role> listAll() {
        return list("archivedAt IS NULL order by name");
    }

    public Optional<Role> findByName(String name) {
        return find("name = ?1 and archivedAt IS NULL", name).firstResultOptional();
    }

    /**
     * The role of {@code tenantId} holding {@code name}, in any lifecycle state: {@code uq_role_name}
     * is {@code (tenant_id, name)} and counts every row. Tenant-scoped explicitly: a conflict must
     * never name another tenant's role, whether or not RLS is enforcing on the connection. Stripped,
     * as {@link Role}'s constructors store it: a padded duplicate meets the 409 here, not the constraint.
     */
    public Optional<Role> holderOfName(Long tenantId, String name) {
        return find("tenantId = ?1 AND name = ?2", tenantId, name.strip()).firstResultOptional();
    }
}
