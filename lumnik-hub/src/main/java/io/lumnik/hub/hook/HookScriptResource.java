package io.lumnik.hub.hook;

import io.lumnik.common.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * The scripts a manifest can name as {@code hooks:<name>.kts} (#1402) — the operator's hooks directory,
 * by name, size and SHA-256, never its path nor its content. {@code directory: false} says the hub has
 * none, which an empty list would not.
 */
@Path("/api/platform/hooks/scripts")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class HookScriptResource {

    @Inject KotlinScriptHost host;

    @GET
    public Map<String, Object> list() {
        return host.hooksScripts()
            .<Map<String, Object>>map(scripts -> Map.of("directory", true, "scripts", scripts))
            .orElse(Map.of("directory", false, "scripts", List.of()));
    }
}
