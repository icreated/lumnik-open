package io.lumnik.identity.roleorgaccess;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RoleOrgAccessRepository implements PanacheRepositoryBase<RoleOrgAccess, RoleOrgAccessId> {

    public List<RoleOrgAccess> listByRole(Long roleId) {
        return list("id.roleId = ?1 and active = true", roleId);
    }

    public Optional<RoleOrgAccess> findGrant(Long roleId, Long orgId) {
        return findByIdOptional(new RoleOrgAccessId(roleId, orgId));
    }

    public boolean grantExists(Long roleId, Long orgId) {
        return count("id.roleId = ?1 and id.orgId = ?2 and active = true", roleId, orgId) > 0;
    }
}
