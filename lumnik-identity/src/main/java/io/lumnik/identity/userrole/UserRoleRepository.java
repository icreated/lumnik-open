package io.lumnik.identity.userrole;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRoleRepository implements PanacheRepositoryBase<UserRole, UserRoleId> {

    public List<UserRole> listByUser(Long userId) {
        return list("id.userId = ?1 and active = true", userId);
    }

    public Optional<UserRole> findAssignment(Long userId, Long roleId) {
        return findByIdOptional(new UserRoleId(userId, roleId));
    }

    public boolean assignmentExists(Long userId, Long roleId) {
        return count("id.userId = ?1 and id.roleId = ?2 and active = true", userId, roleId) > 0;
    }
}
