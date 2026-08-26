package io.lumnik.hub.secret;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SecretRepository implements PanacheRepositoryBase<Secret, UUID> {

    public Optional<Secret> findByName(long tenantId, String name) {
        return find("tenantId = ?1 AND name = ?2 AND deletedAt IS NULL", tenantId, name)
            .firstResultOptional();
    }

    public List<Secret> listByTenant(long tenantId) {
        return list("tenantId = ?1 AND deletedAt IS NULL ORDER BY name", tenantId);
    }
}
