package io.lumnik.hub.security.roles;

import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.common.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/platform/roles")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class RoleResource {

    @Inject RoleRegistry registry;
    @Inject ModuleManifestRegistry manifestRegistry;

    @GET
    public Response list() {
        Map<String, ResolvedPermission> index = buildPermissionIndex();
        List<RoleDto> dtos = registry.all().stream().map(r -> {
            List<PermissionGrantDto> grants = r.permissions().stream()
                .map(pid -> {
                    ResolvedPermission rp = index.get(pid);
                    return new PermissionGrantDto(
                        pid,
                        rp != null ? rp.label : pid,
                        rp != null ? rp.module : "unknown");
                })
                .toList();
            return new RoleDto(
                r.id(), r.label(), r.description(),
                r.realm(), r.wildcard(),
                r.wildcard() ? List.of() : grants);
        }).toList();

        CacheControl cc = new CacheControl();
        cc.setMaxAge(60);
        return Response.ok(new RoleCatalogResponse(dtos)).cacheControl(cc).build();
    }

    private Map<String, ResolvedPermission> buildPermissionIndex() {
        Map<String, ResolvedPermission> index = new HashMap<>();
        for (ModuleManifest m : manifestRegistry.all()) {
            if (m.permissions() == null) continue;
            for (ModuleManifest.ManifestPermission p : m.permissions()) {
                String label = pickLabel(p.label());
                index.put(p.id(), new ResolvedPermission(label, m.id()));
            }
        }
        return index;
    }

    private static String pickLabel(Map<String, String> i18n) {
        if (i18n == null || i18n.isEmpty()) return "";
        String fr = i18n.get("fr");
        if (fr != null) return fr;
        String en = i18n.get("en");
        if (en != null) return en;
        return i18n.values().iterator().next();
    }

    private record ResolvedPermission(String label, String module) {}
}
