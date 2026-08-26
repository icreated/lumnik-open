package io.lumnik.identity.tenant;

import io.lumnik.common.security.RequiresPermission;
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
        t.archive();
        return Response.noContent().build();
    }
}
