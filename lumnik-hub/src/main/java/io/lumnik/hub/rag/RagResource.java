package io.lumnik.hub.rag;

import io.lumnik.common.security.RequiresPermission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/platform/rag")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class RagResource {

    @Inject PlatformIndexer indexer;

    @GET
    @Path("/status")
    @RequiresPermission("lm_admin")
    public RagStatusDto status() {
        return indexer.status();
    }

    @POST
    @Path("/reindex")
    @RequiresPermission("lm_admin")
    public Response reindex() {
        indexer.reindex();
        return Response.accepted(Map.of("status", "reindex_started")).build();
    }
}
