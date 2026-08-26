package io.lumnik.hub.security.roles;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RoleRegistryIT {

    @Inject RoleRegistry registry;

    @Test
    void actualPlatformRolesYaml_loadsCleanly() {
        List<RoleDefinition> roles = registry.all();
        assertFalse(roles.isEmpty(), "Expected at least one role from shipped roles.yaml");
    }

    @Test
    void everyDeclaredRole_isOneOfThreeKnown() {
        Set<String> ids = registry.all().stream().map(RoleDefinition::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.of("lm_superadmin", "lm_admin", "lm_user"), ids,
            "Catalog must contain exactly the three platform roles");
    }

    @Test
    void noRoleExceptSuperadmin_hasWildcard() {
        for (RoleDefinition r : registry.all()) {
            if (r.wildcard()) {
                assertEquals("lm_superadmin", r.id(),
                    "Only lm_superadmin may have wildcard:true; found: " + r.id());
            }
        }
    }

    @Test
    void lmAdmin_grantsAtLeastOneManagePermission() {
        RoleDefinition admin = registry.all().stream()
            .filter(r -> "lm_admin".equals(r.id()))
            .findFirst().orElseThrow();
        assertTrue(admin.permissions().stream().anyMatch(p -> p.endsWith(".manage")),
            "lm_admin must grant at least one *.manage permission");
    }
}
