package io.lumnik.identity.tenant;

import io.lumnik.common.security.RequiresPermission;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tenants")
@RequiresPermission("lm_superadmin")
public class TenantResource {

    private static final String TENANT_NOT_FOUND = "Tenant not found: ";

    @Inject
    TenantRepository tenants;

    @Inject
    Event<TenantArchived> tenantArchived;

    @GET
    @Operation(summary = "List all active tenants")
    public List<TenantDto> listAll() {
        return tenants.listActive().stream().map(TenantDto::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get tenant by ID")
    public TenantDto findById(@PathParam("id") Long id) {
        Tenant t = tenants.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException(TENANT_NOT_FOUND + id));
        return TenantDto.from(t);
    }

    @POST
    @Transactional
    @Operation(summary = "Create a new tenant")
    public Response create(@Valid TenantCommands.Create cmd) {
        if (tenants.codeExists(cmd.code()))
            throw new ClientErrorException("Tenant code already exists: " + cmd.code(), Response.Status.CONFLICT);

        Tenant t = new Tenant(cmd.code(), cmd.name());
        t.setDescription(cmd.description());
        tenants.persist(t);

        return Response.created(UriBuilder.fromResource(TenantResource.class)
                .path("/{id}").build(t.getId()))
            .entity(TenantDto.from(t))
            .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update tenant name and description")
    public TenantDto update(@PathParam("id") Long id, @Valid TenantCommands.Update cmd) {
        Tenant t = tenants.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException(TENANT_NOT_FOUND + id));
        t.setName(cmd.name());
        t.setDescription(cmd.description());
        return TenantDto.from(t);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Deactivate a tenant (archive — the row is kept)")
    public Response deactivate(@PathParam("id") Long id) {
        Tenant t = tenants.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException(TENANT_NOT_FOUND + id));
        // #701 trigger row 7 (design doc §2): fired BEFORE archive(), synchronously, with no
        // try/catch. A synchronous fire() blocks until every @Observes listener returns or one
        // throws; the hub's purge listener throwing here propagates straight out of deactivate(),
        // marks this @Transactional rollback-only, and t.archive() below is never reached — so
        // there is no window where a tenant is archived without its SearchIndex purge queued, and
        // no separate try/catch to silently swallow that failure (contrast with every OTHER #701
        // listener, which is best-effort catch-and-log because a later trigger can repair it; a
        // tenant delete has no later trigger — see the design doc's "Does the tenant-purge
        // listener catch-and-log" row).
        //
        // NOT covered by that guarantee, and impossible to close without cross-datasource XA (this
        // module's own JTA transaction and the hub admin datasource the purge runs on — deliberately
        // JTA-disabled, CLAUDE.md — are two separate connection pools with no shared transaction to
        // join): the reverse order, where the purge above has already committed but t.archive()
        // below then fails for an unrelated reason (this method's own JTA commit). A review of this
        // PR (#709) named this gap explicitly; it is accepted as a documented, narrower compensating
        // semantics rather than fixed, because it is self-healing — purgeAllForTenant's CTE only
        // touches rows still present, so retrying this exact call after such a failure purges
        // nothing a second time and simply completes the archive that failed the first time. This is
        // a materially smaller risk than the ordering it replaces: that failure mode is loud (the
        // caller gets ≥500 and can retry), where the original bug this design closed was silent
        // forever. See SearchIndexTenantPurgeIT's regression proving exactly this recovery path.
        tenantArchived.fire(new TenantArchived(id));
        t.archive();
        return Response.noContent().build();
    }
}
