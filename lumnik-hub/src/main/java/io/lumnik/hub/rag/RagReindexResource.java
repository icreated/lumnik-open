package io.lumnik.hub.rag;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.TenantWide;
import io.lumnik.hub.api.DeploymentWide;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Rebuilds the platform corpus: the manifests, the API and the hooks, which every tenant's chat reads. Its subject is
 * the whole deployment, so a scope-bound caller is refused it (#1992). Apart from {@link RagResource} because
 * {@code @TenantWide} marks a whole class, and the status stays readable to a bound caller.
 */
@TenantWide
@DeploymentWide
@Path("/api/platform/rag/reindex")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class RagReindexResource {

    @Inject PlatformIndexer indexer;

    @POST
    @RequiresPermission("lm_admin")
    public Response reindex() {
        indexer.reindex();
        return Response.accepted(Map.of("status", "reindex_started")).build();
    }
}
