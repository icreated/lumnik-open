package io.lumnik.hub.security.roles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoleRegistryUnitTest {

    @TempDir Path tmp;

    private static final Set<String> KNOWN_PERMS = Set.of(
        "repair.read", "repair.manage",
        "request.read", "request.manage"
    );

    private URL writeYaml(String name, String body) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, body);
        return p.toUri().toURL();
    }

    @Test
    void validRolesYaml_parsesAndExposes() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_admin
                label: "Admin"
                description: "Full admin"
                realm: true
                permissions:
                  - repair.manage
                  - request.manage
            """);
        RoleRegistry r = new RoleRegistry();
        r.loadFrom(List.of(url), KNOWN_PERMS);
        assertEquals(1, r.all().size());
        assertEquals("lm_admin", r.all().get(0).id());
        assertEquals(2, r.all().get(0).permissions().size());
    }

    @Test
    void unknownPermissionReference_throws() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_admin
                label: "Admin"
                description: "Full admin"
                realm: true
                permissions:
                  - repair.foo
            """);
        RoleRegistry r = new RoleRegistry();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(url), KNOWN_PERMS));
        assertTrue(ex.getMessage().contains("repair.foo"),
            "Message should mention the unknown permission: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("repair.read"),
            "Message should list known permissions: " + ex.getMessage());
    }

    @Test
    void duplicateRoleId_throws() throws Exception {
        URL url1 = writeYaml("a.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_admin
                label: "Admin A"
                description: "From A"
                realm: true
                permissions: [repair.read]
            """);
        URL url2 = writeYaml("b.yaml", """
            module: other
            version: "0.1"
            roles:
              - id: lm_admin
                label: "Admin B"
                description: "From B"
                realm: true
                permissions: [request.read]
            """);
        RoleRegistry r = new RoleRegistry();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(url1, url2), KNOWN_PERMS));
        assertTrue(ex.getMessage().contains("lm_admin"),
            "Message should mention the duplicate id: " + ex.getMessage());
    }

    @Test
    void wildcardWithPermissions_throws() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_superadmin
                label: "Super"
                description: "Bypass"
                realm: true
                wildcard: true
                permissions: [repair.read]
            """);
        RoleRegistry r = new RoleRegistry();
        assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(url), KNOWN_PERMS));
    }

    @Test
    void missingLabel_throws() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_admin
                label: ""
                description: "x"
                realm: true
                permissions: [repair.read]
            """);
        RoleRegistry r = new RoleRegistry();
        assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(url), KNOWN_PERMS));
    }

    @Test
    void invalidIdPattern_throws() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: "Lm-Admin"
                label: "X"
                description: "X"
                realm: true
                permissions: [repair.read]
            """);
        RoleRegistry r = new RoleRegistry();
        assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(url), KNOWN_PERMS));
    }

    // ── grants: a module adds its own permissions to a role it does not own ──────────────────
    //
    // The open edition ships without lumnik-rag, so rag.* must not be named by the open hub's
    // roles.yaml at all — a role referencing a permission no module declares fails the boot, by
    // design. A module that ships its permissions ships the grants for them, and both vanish
    // together when the module is absent (publication ritual, Act 1 seam 3).

    private static final String PLATFORM_ROLES = """
        module: platform
        version: "0.1"
        roles:
          - id: lm_admin
            label: "Admin"
            description: "Full admin"
            realm: true
            permissions:
              - repair.manage
        """;

    @Test
    void aModuleGrantsItsOwnPermissionsToARoleItDoesNotOwn() throws Exception {
        URL platform = writeYaml("roles.yaml", PLATFORM_ROLES);
        URL module = writeYaml("module-roles.yaml", """
            module: request
            version: "0.1"
            grants:
              - role: lm_admin
                permissions:
                  - request.manage
                  - request.read
            """);
        RoleRegistry r = new RoleRegistry();
        r.loadFrom(List.of(platform, module), KNOWN_PERMS);

        assertEquals(1, r.all().size(), "a grant adds permissions, it does not add a role");
        assertEquals(List.of("repair.manage", "request.manage", "request.read"),
            r.all().get(0).permissions());
    }

    @Test
    void withoutTheModule_theRoleSimplyHasFewerPermissions() throws Exception {
        // The whole point: the same platform file boots alone. This is the open edition.
        RoleRegistry r = new RoleRegistry();
        r.loadFrom(List.of(writeYaml("roles.yaml", PLATFORM_ROLES)), KNOWN_PERMS);

        assertEquals(List.of("repair.manage"), r.all().get(0).permissions());
    }

    @Test
    void aGrantOntoAnUnknownRole_throws() throws Exception {
        // A typo here would silently grant nothing. Roles are security surface: fail loudly.
        URL module = writeYaml("module-roles.yaml", """
            module: request
            version: "0.1"
            grants:
              - role: lm_amdin
                permissions:
                  - request.read
            """);
        RoleRegistry r = new RoleRegistry();
        var e = assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(writeYaml("roles.yaml", PLATFORM_ROLES), module), KNOWN_PERMS));
        assertTrue(e.getMessage().contains("lm_amdin"), e.getMessage());
    }

    @Test
    void aGrantOfAnUndeclaredPermission_throws() throws Exception {
        URL module = writeYaml("module-roles.yaml", """
            module: request
            version: "0.1"
            grants:
              - role: lm_admin
                permissions:
                  - request.invent
            """);
        RoleRegistry r = new RoleRegistry();
        var e = assertThrows(IllegalStateException.class,
            () -> r.loadFrom(List.of(writeYaml("roles.yaml", PLATFORM_ROLES), module), KNOWN_PERMS));
        assertTrue(e.getMessage().contains("request.invent"), e.getMessage());
    }

    @Test
    void aGrantIsAppliedWhicheverOrderTheClasspathHandsTheFilesOver() throws Exception {
        // getResources() order is not specified — a grant must not depend on arriving second.
        URL platform = writeYaml("roles.yaml", PLATFORM_ROLES);
        URL module = writeYaml("module-roles.yaml", """
            module: request
            version: "0.1"
            grants:
              - role: lm_admin
                permissions:
                  - request.read
            """);
        RoleRegistry r = new RoleRegistry();
        r.loadFrom(List.of(module, platform), KNOWN_PERMS);

        assertEquals(List.of("repair.manage", "request.read"), r.all().get(0).permissions());
    }

    @Test
    void wildcardRole_hasEmptyPermissions() throws Exception {
        URL url = writeYaml("roles.yaml", """
            module: platform
            version: "0.1"
            roles:
              - id: lm_superadmin
                label: "Super"
                description: "Bypass"
                realm: true
                wildcard: true
            """);
        RoleRegistry r = new RoleRegistry();
        r.loadFrom(List.of(url), KNOWN_PERMS);
        RoleDefinition role = r.all().get(0);
        assertTrue(role.wildcard());
        assertTrue(role.permissions().isEmpty());
    }
}
