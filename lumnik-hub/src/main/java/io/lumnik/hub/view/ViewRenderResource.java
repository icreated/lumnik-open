package io.lumnik.hub.view;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a kind:View: parse the manifest, resolve it against the scope's SchemaCard (honesty —
 * an unknown column is refused), read the rows read-only under the caller's tenant. The first
 * per-métier app surface. Tenant comes only from the auth context (never a client header).
 */
@Path("/api/views")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class ViewRenderResource {

    @Inject SchemaCardStore cards;
    @Inject ViewDataReader reader;

    @jakarta.inject.Inject io.lumnik.hub.ask.ScopeFreshness freshness;

    private final ViewManifestParser parser = new ViewManifestParser();
    private final ViewResolver resolver = new ViewResolver();

    public record RenderResponse(String title, List<ResolvedView.Column> columns,
                                 List<Map<String,Object>> rows, boolean truncated,
                                 String dataAsOf) {}

    @POST
    @Path("/render")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response render(@QueryParam("filter") List<String> filters, String body) {
        long tenant = RequireTenant.current();

        ViewManifest manifest;
        try {
            manifest = parser.parse(body);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }

        if (!ScopeGrants.allows(manifest.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        Optional<SchemaCard> card = cards.load(tenant, manifest.scope());
        if (card.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no schema for scope '" + manifest.scope() + "' — run discovery first")).build();
        }

        ResolvedView rv;
        List<ViewFilter> viewFilters;
        try {
            rv = resolver.resolve(manifest, card.get());
            SchemaCard.Table table = card.get().tables().stream()
                    .filter(t -> t.name().equals(rv.table())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "resolved table '" + rv.table() + "' is not in the scope's schema"));
            viewFilters = ViewFilter.resolveAll(filters, rv, table);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }

        ViewDataReader.ReadResult read;
        try {
            read = reader.read(tenant, rv, viewFilters);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }
        String dataAsOf = freshness.lastCompletedRun(tenant, manifest.scope())
            .map(java.time.Instant::toString).orElse(null);
        return Response.ok(new RenderResponse(rv.title(), rv.columns(), read.rows(), read.truncated(), dataAsOf))
            .build();
    }
}
