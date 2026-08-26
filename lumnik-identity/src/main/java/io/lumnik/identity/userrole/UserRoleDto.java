package io.lumnik.identity.userrole;

import java.time.Instant;

public record UserRoleDto(
    Long userId,
    Long roleId,
    Long tenantId,
    boolean active,
    Instant createdAt
) {
    static UserRoleDto from(UserRole ur) {
        return new UserRoleDto(
            ur.getUserId(), ur.getRoleId(), ur.getTenantId(),
            ur.isActive(), ur.getCreatedAt()
        );
    }
}
