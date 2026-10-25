package io.lumnik.identity.tenant;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TenantRepository implements PanacheRepository<Tenant> {

    public List<Tenant> listActive() {
        return list("archivedAt IS NULL order by name");
    }

    public Optional<Tenant> findByCode(String code) {
        return find("code = ?1 and archivedAt IS NULL", code).firstResultOptional();
    }

    /**
     * The tenant holding {@code code} in any lifecycle state: {@code uq_tenant_code} counts every row.
     * Stripped, as {@link Tenant}'s constructor stores it: a padded duplicate meets the 409 here, not
     * the constraint.
     */
    public Optional<Tenant> holderOfCode(String code) {
        return find("code = ?1", code.strip()).firstResultOptional();
    }
}
