package io.lumnik.hub.hook;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class HookTenantOverrideRepository
    implements PanacheRepositoryBase<HookTenantOverride, HookTenantOverride.PK> {

    public Optional<HookTenantOverride> find(Long tenantId, String hookId) {
        return find("tenantId = ?1 AND hookId = ?2", tenantId, hookId).firstResultOptional();
    }

    public List<HookTenantOverride> findAllForTenant(Long tenantId) {
        return list("tenantId", tenantId);
    }

    public List<HookTenantOverride> listAllOverrides() {
        return listAll();
    }
}
