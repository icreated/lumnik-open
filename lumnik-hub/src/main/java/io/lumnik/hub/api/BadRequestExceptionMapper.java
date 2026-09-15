package io.lumnik.hub.api;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Every 400 the hub THROWS carries the frozen {@code {"error": …}} body ({@link ApiErrors#badRequest}).
 *
 * <p>{@code new BadRequestException(message)} sets the JAVA exception's message and builds a response
 * with NO entity, so eleven doors refused bodylessly — {@code lm} printed "API error 400 (no details
 * from the server)" ({@code client.go}) while the sentence naming the problem sat unread in a stack
 * trace. The 404 twin of this gap is {@link NotFoundExceptionMapper}; the knowledge was already in the
 * codebase three times as PROSE — {@code EntityApplyResource} and {@code OrgResource} each hand-built a
 * Response around it, and {@link ApiErrors#badRequest}'s javadoc names the drift — which is three
 * explanations where one mechanism was missing.
 *
 * <p>What it actually absorbed, since "one mechanism" could imply more than happened: the eleven silent
 * sites, and {@code EntityApplyResource}, now back on the plain constructor. {@code OrgResource} keeps
 * its hand-built Response for a structural reason — {@code lumnik-identity} does not depend on this
 * module, and its own {@code OrgTreeIT} boots a deployment where this provider is absent.
 *
 * <p><b>A response that already carries an entity is returned untouched.</b> Not defensive coding: the
 * throw-with-Response form is in live use — {@link ApiErrors#uuidOrRefuse}, {@code EntityApplyResource},
 * {@code OrgResource} — and rebuilding from {@code getMessage()} would replace {@code invalid UUID:
 * warehouses} with {@code HTTP 400 Bad Request}, the message the JAX-RS constructor synthesises from
 * the status. This mapper would then have DELETED the very bodies it exists to guarantee.
 * {@code RunAdminResourceTest} pins that path and goes red if this guard is dropped.
 *
 * <p>Deployment-wide, as a mapper is by nature — so its reach was read off the socket rather than
 * asserted. NEITHER framework-raised 400 arrives here: a JSON field of the wrong type is answered by
 * Quarkus' own {@code JsonProcessingException} mapper in its {@code objectName}/{@code attributeName}
 * shape, and a body Jackson cannot parse at all is refused inside the reader and still comes back with
 * an empty entity. That second one is a REAL residue of the same harm, named rather than claimed
 * closed: dressing it means overriding Quarkus' mapper, which would also change the well-formed shape.
 * Both are pinned in {@code BadRequestExceptionMapperIT} so this paragraph cannot go stale unseen.
 */
@Provider
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {

    @Override
    public Response toResponse(BadRequestException ex) {
        Response existing = ex.getResponse();
        if (existing != null && existing.hasEntity()) return existing;
        String why = ex.getMessage();
        return ApiErrors.badRequest(why == null || why.isBlank() ? "bad request" : why);
    }
}
