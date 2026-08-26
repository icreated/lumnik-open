package io.lumnik.identity.tenant;

import java.time.Instant;

public record TenantDto(
    Long id,
    String code,
    String name,
    String description,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    static TenantDto from(Tenant t) {
        return new TenantDto(
            t.getId(), t.getCode(), t.getName(), t.getDescription(),
            t.isActive(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
