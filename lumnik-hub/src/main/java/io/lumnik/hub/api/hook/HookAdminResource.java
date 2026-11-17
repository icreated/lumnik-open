package io.lumnik.hub.api.hook;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.TenantWide;
import io.lumnik.hub.hook.HookBinding;
import io.lumnik.hub.hook.HookRegistry;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

@Path("/api/platform/admin/hooks")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
@TenantWide
public class HookAdminResource {

  @Inject HookRegistry registry;

  /** The caller's tenant's hooks only, as {@code /api/platform/hooks} lists them: never another tenant's id (#1992). */
  @GET
  public List<Map<String, Object>> list() {
    long tenant = RequireTenant.current();
    return registry.bindingsFor(tenant).stream()
        .map(b -> toDto(b, tenant))
        .toList();
  }

  private static Map<String, Object> toDto(HookBinding b, long tenant) {
    String type = b.isLifecycle()
        ? (b.events() == null ? "lifecycle" : b.events().toString())
        : "action:" + b.action();
    String entity = b.targetClassName() != null ? b.targetClassName() : "—";
    return Map.<String, Object>of(
        "id", b.id(),
        "module", b.module() == null ? "" : b.module(),
        "type", type,
        "entity", entity,
        "scope", b.isGlobal() ? "global" : b.tenantsAsSeenBy(tenant).toString(),
        "status", b.enabled() ? "LOADED" : "DISABLED",
        "priority", b.priority()
    );
  }
}
