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

    public boolean codeExists(String code) {
        return count("code = ?1", code) > 0;
    }
}
