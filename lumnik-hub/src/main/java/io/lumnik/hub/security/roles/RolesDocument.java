package io.lumnik.hub.security.roles;

import java.util.List;

public record RolesDocument(
    String module,
    String version,
    List<RoleDefinition> roles
) {
    public RolesDocument {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
