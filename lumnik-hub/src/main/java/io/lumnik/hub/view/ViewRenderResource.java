package io.lumnik.hub.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.ask.ScopeFreshness;
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

import java.time.Instant;
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
    @Inject ViewCursors cursors;

    @Inject ScopeFreshness freshness;

    private final ViewManifestParser parser = new ViewManifestParser();
    private final ViewResolver resolver = new ViewResolver();

    /**
     * {@code truncated} stays the honest "more rows exist than shown" (the 1 000-row cut, or the
     * end of a page); {@code hasMore} says the same for the paged contract, and {@code nextCursor}
     * (null on the last page and on an unpaged read) is the opaque token that continues it.
     */
    public record RenderResponse(String title, List<ResolvedView.Column> columns,
                                 List<Map<String,Object>> rows, boolean truncated,
                                 String dataAsOf, boolean hasMore, String nextCursor,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ViewLink> links) {}

    @POST
    @Path("/render")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response render(@QueryParam("filter") List<String> filters,
                           @QueryParam("pageSize") Integer pageSize,
                           @QueryParam("cursor") String cursor, String body) {
        long tenant = RequireTenant.current();

        ViewManifest manifest = ApiErrors.orBadRequest(() -> parser.parse(body));

        if (!ScopeGrants.allows(manifest.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        Optional<SchemaCard> card = cards.load(tenant, manifest.scope());
        if (card.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no schema for scope '" + manifest.scope() + "' — run discovery first")).build();
        }

        ResolvedView rv = ApiErrors.orBadRequest(() -> resolver.resolve(manifest, card.get()));
        List<ViewFilter> viewFilters = ApiErrors.orBadRequest(() -> {
            SchemaCard.Table table = card.get().tables().stream()
                    .filter(t -> t.name().equals(rv.table())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "resolved table '" + rv.table() + "' is not in the scope's schema"));
            return ViewFilter.resolveAll(filters, rv, table);
        });

        String dataAsOf = freshness.lastCompletedRun(tenant, manifest.scope())
            .map(Instant::toString).orElse(null);
        boolean paged = pageSize != null || (cursor != null && !cursor.isBlank());
        if (!paged) {
            ViewDataReader.ReadResult read = ApiErrors.orBadRequest(() -> reader.read(tenant, rv, viewFilters));
            return Response.ok(new RenderResponse(rv.title(), rv.columns(), wired(rv, read.rows()), read.truncated(),
                dataAsOf, read.truncated(), null, manifest.links())).build();
        }

        SealedPage sealed = ApiErrors.orBadRequest(() -> readPage(tenant, manifest, rv, viewFilters, pageSize, cursor));
        ViewDataReader.PageResult page = sealed.page();
        return Response.ok(new RenderResponse(rv.title(), rv.columns(), wired(rv, page.rows()), page.hasMore(),
            dataAsOf, page.hasMore(), sealed.next(), manifest.links())).build();
    }

    /** The rows as JSON writes them ({@link ResolvedView#wire}). The reader keeps the JDBC types; text is the wire's business. */
    private static List<Map<String, Object>> wired(ResolvedView rv, List<Map<String, Object>> rows) {
        rows.forEach(r -> r.replaceAll((column, v) -> rv.wire(v)));
        return rows;
    }

    /** One page and the sealed cursor to the next one, null on the last page. */
    private record SealedPage(ViewDataReader.PageResult page, String next) {}

    private SealedPage readPage(long tenant, ViewManifest manifest, ResolvedView rv, List<ViewFilter> viewFilters,
                                Integer pageSize, String cursor) {
        int size = ViewPageContract.resolvePageSize(pageSize);
        boolean hasId = ViewPageContract.relationHasId(rv.table());
        String binding = ViewPageContract.binding(tenant, manifest.scope(), manifest.name(), rv,
            ViewPageContract.orderKey(rv, hasId), viewFilters, size);
        ViewCursor.Boundary after = cursor == null || cursor.isBlank() ? null : cursors.open(binding, cursor);
        ViewDataReader.PageResult page = reader.readPage(tenant, rv, viewFilters, hasId, new ViewDataReader.Page(size, after));
        return new SealedPage(page, page.next() == null ? null : cursors.seal(binding, page.next()));
    }
}
