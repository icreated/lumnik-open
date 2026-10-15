package io.lumnik.hub.search;

import io.lumnik.common.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-neutral capability discovery (#668): lets a client tell "Search is not installed" from
 * "Search is down" before it ever asks a question. Always {@code 200} — the body is the answer.
 */
@Path("/api/search/capability")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Search")
@RequiresPermission("lm_user")
public class SearchCapabilityResource {

    @Inject SearchGate gate;

    @GET
    @Operation(summary = "Whether direct Search is available, not installed, or degraded")
    public Map<String, Object> capability() {
        return body(gate.capability());
    }

    /** {@code {"state":"unavailable","provider":null,"reason":"not_installed","version":null}} — keys
     *  always present. */
    static Map<String, Object> body(SearchCapability c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", c.state().name().toLowerCase(Locale.ROOT));
        body.put("provider", c.provider());
        body.put("reason", c.reason());
        body.put("version", c.version());
        return body;
    }
}
