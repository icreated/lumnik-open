package io.lumnik.hub.api.sources;

import io.lumnik.hub.api.connector.ConnectorScope;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.source.csv.CsvIngestService;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/**
 * One-shot CSV ingestion endpoint. Tenant comes from the auth-resolved context
 * (JWT in prod; in dev-bypass the X-Tenant-Id header is honored via TenantContextFilter).
 *
 * <pre>
 * POST /api/platform/sources/csv/ingest
 *   Content-Type: multipart/form-data
 *
 *   file=&lt;binary&gt;  name=customers  scope=billing
 * </pre>
 */
@ApplicationScoped
@Path("/api/platform/sources/csv")
@Produces(MediaType.APPLICATION_JSON)
public class CsvIngestResource {

    @Inject CsvIngestService service;
    @Inject AuditRecorder audit;

    @POST
    @Path("/ingest")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public CsvIngestResult ingest(@BeanParam CsvIngestForm form) {
        // The tags the one-shot will carry — its scope, "system" when none is given — must all be
        // the caller's (#1212, the apply door's rule); a re-ingest also asks about the tags it replaces.
        ConnectorScope.refuseUnlessHolds(CsvIngestService.connectorTags(form));
        CsvIngestResult result = service.execute(form, RequireTenant.current());
        // The one-shot creates (or, on a breath, rewrites) a dormant connector: a connector write (#1193).
        audit.record("Connector", result.connectorId().toString(), "APPLY", Map.of(
            "via", "csv-ingest", "table", result.tableName(), "tags", result.askableScopes(),
            "refreshed", result.refreshed()));
        return result;
    }

    /**
     * The service refuses unusable request shapes with {@link IllegalArgumentException} — a missing
     * upload, or a name over a multi-file source (A11). Unmapped, those surface as a 500 whose
     * message Quarkus withholds in production: a refusal nobody can read teaches nothing. Scoped to
     * this resource (declared inside it), so no other endpoint's error contract moves, and the
     * shape matches the house's — {@code {"error": "..."}}.
     */
    /** A taken ingest name is a conflict with its own gestures, not an anonymous 500. */
    @ServerExceptionMapper
    public Response duplicate(CsvIngestService.DuplicateIngestName e) {
        return Response.status(Response.Status.CONFLICT)
            .entity(Map.of("error", e.getMessage()))
            .build();
    }

    @ServerExceptionMapper
    public Response refused(IllegalArgumentException e) {
        // A message-less IllegalArgumentException is legal Java, and Map.of rejects null values —
        // the mapper that exists to prevent unreadable 500s must not become the cause of one.
        // Falling back to toString() keeps the answer truthful rather than inventing a reason;
        // /api/platform/* is integrator-only, so the type name is no leak.
        String reason = e.getMessage() != null ? e.getMessage() : e.toString();
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", reason))
            .build();
    }
}
