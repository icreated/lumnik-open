package io.lumnik.hub.hook;

import java.util.Set;

public record UserContext(Long id, String name, Set<String> roles) {
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
