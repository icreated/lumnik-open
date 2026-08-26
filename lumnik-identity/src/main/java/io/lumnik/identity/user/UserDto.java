package io.lumnik.identity.user;

import java.time.Instant;

public record UserDto(
    Long id,
    Long tenantId,
    String login,
    String name,
    String email,
    String description,
    boolean active,
    boolean locked,
    Instant lastLoginAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static UserDto from(User u) {
        return new UserDto(
            u.getId(), u.getTenantId(), u.getLogin(), u.getName(),
            u.getEmail(), u.getDescription(), u.isActive(), u.isLocked(),
            u.getLastLoginAt(),
            u.getCreatedAt(), u.getUpdatedAt()
        );
    }
}
