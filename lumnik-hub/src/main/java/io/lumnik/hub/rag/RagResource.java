package io.lumnik.hub.rag;

import io.lumnik.common.security.RequiresPermission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * The RAG index's status. A scope-bound caller reads it too: {@code storedChunks} counts only the chunks of the
 * scopes it holds ({@link StoredChunks}, #1895). The reindex, which rebuilds the corpus every tenant shares, is
 * {@link RagReindexResource}'s.
 */
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
}
