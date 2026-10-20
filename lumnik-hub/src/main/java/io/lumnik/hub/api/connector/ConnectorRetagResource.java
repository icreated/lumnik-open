package io.lumnik.hub.api.connector;

import io.lumnik.extraction.spi.ChunkRetagger;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/platform/connectors/{id}/retag")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectorRetagResource {

  @Inject ConnectorRepository repo;
  /** The chunk store's side of the seam (closed edition); absent → zero chunks to retag. */
  @Inject Instance<ChunkRetagger> retaggers;

  @POST
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response retag(@PathParam("id") UUID id) {
    // Held: the tags pushed are the ones a concurrent retag commits, never the ones read before (#1217).
    var c = ConnectorScope.heldForUpdate(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    var tags = c.getTags() != null ? c.getTags() : List.<String>of();

    // Through the seam, never the rag schema directly (Act 2 dry-run). No store on the
    // classpath = no chunks exist = zero updated is the honest answer.
    int updated = retaggers.isUnsatisfied() ? 0 : retaggers.get().retagByConnector(id, tags);
    return Response.ok(Map.of("chunksUpdated", updated)).build();
  }
}
