package io.lumnik.hub.api;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Every 404 the hub emits carries the frozen {@code {"error": …}} body ({@link ApiErrors#notFound}).
 * A resource that returns a domain type (not {@code Response}) says "not found" by THROWING
 * {@code NotFoundException}; without this mapper that reached the client bodyless — the throw-shaped
 * twin of the bare {@code Response.status(404).build()} sites #418 fixed, and the channel
 * {@code NotFoundBodyInventoryTest} named as its blind spot.
 *
 * <p>Deployment-wide, and verified safe: it also dresses a framework 404 (an unmatched route), whose
 * default message is the benign generic "HTTP 404 Not Found" — no internal path — replacing RESTEasy's
 * {@code text/html} page with JSON; the PWA has no client-side router, so no {@code /app/…} deep link
 * relies on that fallback. A thrown message is echoed; an absent one falls back to a generic string.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException ex) {
        String msg = ex.getMessage();
        return ApiErrors.notFound(msg == null || msg.isBlank() ? "not found" : msg);
    }
}
