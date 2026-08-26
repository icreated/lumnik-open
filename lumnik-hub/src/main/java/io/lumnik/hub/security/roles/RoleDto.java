package io.lumnik.hub.security.roles;

import java.util.List;

public record RoleDto(
    String id,
    String label,
    String description,
    boolean realm,
    boolean wildcard,
    List<PermissionGrantDto> permissions
) {}
