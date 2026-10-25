package io.lumnik.identity;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The identity twin of the hub's {@code ApiErrors}, as {@code RagErrors} is rag's: the
 * {@code {"error": "…"}} body, typed JSON. This module cannot depend on the hub, and its own tests
 * boot without it.
 */
public final class IdentityErrors {

    private IdentityErrors() {}

    /**
     * A 409 that names what is taken and what holds it. The message must travel in the response:
     * {@code new ClientErrorException(message, CONFLICT)} keeps it on the exception, and the caller
     * reads a bare 409 with an empty body. Thrown, not returned: the role update door answers with a
     * DTO and the org check runs inside a transaction lambda, so a returned {@code Response} would
     * have nowhere to go.
     */
    public static ClientErrorException conflict(String what) {
        return new ClientErrorException(Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON).entity(Map.of("error", what)).build());
    }
}
