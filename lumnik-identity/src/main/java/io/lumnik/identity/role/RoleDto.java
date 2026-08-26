package io.lumnik.identity.role;

import java.time.Instant;

public record RoleDto(
    Long id,
    Long tenantId,
    String name,
    String description,
    String userLevel,
    boolean manual,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    static RoleDto from(Role r) {
        return new RoleDto(
            r.getId(), r.getTenantId(), r.getName(), r.getDescription(),
            r.getUserLevel().getCode(), r.isManual(), r.isActive(),
            r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
