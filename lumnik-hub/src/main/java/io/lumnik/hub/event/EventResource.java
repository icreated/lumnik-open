package io.lumnik.hub.event;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/platform/events")
@Produces(MediaType.APPLICATION_JSON)
public class EventResource {

    @Inject EventRegistry registry;

    @GET
    public List<EventCatalog.EventDescriptor> list() {
        return registry.all();
    }
}
