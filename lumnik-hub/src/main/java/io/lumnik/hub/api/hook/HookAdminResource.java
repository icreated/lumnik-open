package io.lumnik.hub.api.hook;

import io.lumnik.hub.hook.HookBinding;
import io.lumnik.hub.hook.HookRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

@Path("/api/platform/admin/hooks")
@Produces(MediaType.APPLICATION_JSON)
public class HookAdminResource {

  @Inject HookRegistry registry;

  @GET
  public List<Map<String, Object>> list() {
    return registry.allBindings().stream()
        .map(HookAdminResource::toDto)
        .toList();
  }

  private static Map<String, Object> toDto(HookBinding b) {
    String type = b.isLifecycle()
        ? (b.events() == null ? "lifecycle" : b.events().toString())
        : "action:" + b.action();
    String entity = b.targetClassName() != null ? b.targetClassName() : "—";
    return Map.<String, Object>of(
        "id", b.id(),
        "module", b.module() == null ? "" : b.module(),
        "type", type,
        "entity", entity,
        "scope", b.tenants() == null || b.tenants().isEmpty() ? "global" : b.tenants().toString(),
        "status", b.enabled() ? "LOADED" : "DISABLED",
        "priority", b.priority()
    );
  }
}
