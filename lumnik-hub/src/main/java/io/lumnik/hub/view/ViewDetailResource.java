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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders the drill-down detail of one kind:View record, addressed by its first declared column
 * (the k9s identity), with the fused-entity `_conflicts` ledger first-class. Tenant from the auth
 * context only. 404 when no record matches; 400 when the first column is not unique.
 */
@Path("/api/views")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class ViewDetailResource {

    @Inject SchemaCardStore cards;
    @Inject ViewDetailReader reader;

    private final ViewManifestParser parser = new ViewManifestParser();
    private final ViewResolver resolver = new ViewResolver();

    public record Field(String name, String label, Object value) {}
    public record DetailResponse(String title, String key, List<Field> fields,
                                 boolean conflictLedger, List<ConflictLedger.Conflict> conflicts) {}

    @POST
    @Path("/detail")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response detail(@QueryParam("key") String key, String body) {
        long tenant = RequireTenant.current();

        if (key == null || key.isBlank()) {
            return Response.status(400).entity(ApiErrors.error("the 'key' query param is required")).build();
        }

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
        try {
            rv = resolver.resolve(manifest, card.get());
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }

        String firstCol = rv.columns().get(0).name();
        ViewDetailReader.DetailRead read = reader.read(tenant, rv, key);
        if (read.rows().isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no record where " + firstCol + "=" + key)).build();
        }
        if (read.rows().size() > 1) {
            return Response.status(400).entity(ApiErrors.error(
                "ambiguous: " + firstCol + " is not unique — 2+ records match " + key)).build();
        }

        ViewDetailReader.DetailRow row = read.rows().get(0);
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < rv.columns().size(); i++) {
            ResolvedView.Column col = rv.columns().get(i);
            fields.add(new Field(col.name(), col.label(), row.values().get(i)));
        }
        List<ConflictLedger.Conflict> conflicts = read.ledgerPresent()
            ? ConflictLedger.parse(row.conflictsJson(), rv) : List.of();

        return Response.ok(new DetailResponse(rv.title(), key, fields, read.ledgerPresent(), conflicts)).build();
    }
}
