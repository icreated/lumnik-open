package io.lumnik.identity.roleorgaccess;

import java.time.Instant;

public record RoleOrgAccessDto(
    Long roleId,
    Long orgId,
    Long tenantId,
    boolean readOnly,
    boolean active,
    Instant createdAt
) {
    static RoleOrgAccessDto from(RoleOrgAccess r) {
        return new RoleOrgAccessDto(
            r.getRoleId(), r.getOrgId(), r.getTenantId(),
            r.isReadOnly(), r.isActive(), r.getCreatedAt()
        );
    }
}
