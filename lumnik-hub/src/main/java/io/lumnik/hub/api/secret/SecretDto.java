package io.lumnik.hub.api.secret;

import io.lumnik.hub.secret.Secret;

import java.time.Instant;
import java.util.UUID;

public record SecretDto(
    UUID id, String name, String description,
    Instant createdAt, Long createdBy,
    Instant updatedAt, Long updatedBy,
    Instant rotatedAt
) {
    public static SecretDto from(Secret s) {
        return new SecretDto(
            s.getId(), s.getName(), s.getDescription(),
            s.getCreatedAt(), s.getCreatedBy(),
            s.getUpdatedAt(), s.getUpdatedBy(),
            s.getRotatedAt()
        );
    }
}
