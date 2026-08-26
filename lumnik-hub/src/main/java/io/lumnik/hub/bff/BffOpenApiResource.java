package io.lumnik.hub.bff;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;

@Path("/api/bff/v1/openapi.json")
@Tag(name = "BFF")
public class BffOpenApiResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "BFF OpenAPI spec — redirects to the platform aggregate spec")
    public Response spec(@Context UriInfo uriInfo) {
        // Phase 4: redirect to the aggregate spec. Filtered BFF-only spec in Phase 5.
        URI target = uriInfo.getBaseUri().resolve("/q/openapi?format=json");
        return Response.temporaryRedirect(target).build();
    }
}
