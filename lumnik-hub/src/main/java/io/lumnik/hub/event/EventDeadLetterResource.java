package io.lumnik.hub.event;

import io.lumnik.hub.api.ApiErrors;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * The dead letters of the tenant's outbox consumers (#1837): what a consumer failed on, and the door to redo it. Under
 * {@code /api/platform}, so integrator-only like the rest of it; the tenant is the caller's.
 */
@Path("/api/platform/events/dead-letters")
@Produces(MediaType.APPLICATION_JSON)
public class EventDeadLetterResource {

    private static final Logger LOG = Logger.getLogger(EventDeadLetterResource.class);

    @Inject EventDeadLetters letters;

    /** @param open {@code true} lists only what is not replayed yet; the most recent {@value EventDeadLetters#LIST_LIMIT} either way */
    @GET
    @Transactional
    public Response list(@QueryParam("consumer") String consumer, @QueryParam("open") @DefaultValue("false") boolean open) {
        return Response.ok(letters.list(consumer, open)).build();
    }

    @POST
    @Path("/{id}/replay")
    public Response replay(@PathParam("id") long id) {
        EventDeadLetters.Replay replay;
        try {
            replay = letters.replay(id);
        } catch (RuntimeException e) {
            // The class only: a message can quote a row (#1090). The log keeps the rest, the letter stays open.
            LOG.errorf(e, "replay of dead letter %d failed", id);
            return ApiErrors.status(500, "replay failed (" + EventDeadLetters.describe(e) + ")").build();
        }
        return switch (replay.status()) {
            case DONE -> Response.ok().build();
            case NOT_FOUND -> ApiErrors.notFound("dead letter " + id);
            case ALREADY_REPLAYED -> ApiErrors.conflict("dead letter " + id + " was already replayed");
            case NO_REPLAY -> ApiErrors.conflict("consumer '" + replay.consumer() + "' has no replay");
        };
    }
}
