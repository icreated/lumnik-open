package io.lumnik.hub.security.roles;

import java.util.List;

public record RoleDefinition(
    String id,
    String label,
    String description,
    boolean realm,
    boolean wildcard,
    List<String> permissions
) {
    public RoleDefinition {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
