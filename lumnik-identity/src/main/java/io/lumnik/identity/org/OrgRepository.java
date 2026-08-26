package io.lumnik.identity.org;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OrgRepository implements PanacheRepository<Organization> {

    public List<Organization> listAll() {
        return list("archivedAt IS NULL order by name");
    }

    public Optional<Organization> findByCode(String code) {
        return find("code = ?1 and archivedAt IS NULL", code).firstResultOptional();
    }

    public Optional<Organization> findByTenantAndId(Long tenantId, Long id) {
        return find("tenantId = ?1 and id = ?2", tenantId, id).firstResultOptional();
    }

    /** Includes archived/deleted rows — callers using it as a walk bound must cover them. */
    public long countByTenant(Long tenantId) {
        return count("tenantId = ?1", tenantId);
    }

    public boolean codeExists(String code) {
        return count("code = ?1", code) > 0;
    }

    public List<Organization> findChildren(Long tenantId, Long parentOrgId) {
        return list("tenantId = ?1 AND parentOrgId = ?2 AND archivedAt IS NULL AND deletedAt IS NULL ORDER BY name",
                    tenantId, parentOrgId);
    }

    public List<Organization> findRoots(Long tenantId) {
        return list("tenantId = ?1 AND parentOrgId IS NULL AND archivedAt IS NULL AND deletedAt IS NULL ORDER BY name",
                    tenantId);
    }

    public List<Organization> findByTenantWithCoords(Long tenantId) {
        return list("tenantId = ?1 AND latitude IS NOT NULL AND longitude IS NOT NULL "
                  + "AND archivedAt IS NULL AND deletedAt IS NULL", tenantId);
    }
}
