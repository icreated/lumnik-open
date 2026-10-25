package io.lumnik.identity.role;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.IdentityErrors;
import io.lumnik.identity.Require;
import io.lumnik.identity.TenantInPath;
import io.lumnik.identity.tenant.TenantRepository;
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

@TenantInPath
@Path("/api/tenants/{tenantId}/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Roles")
@RequiresPermission("lm_admin")
public class RoleResource {

    @Inject RoleRepository roles;
    @Inject TenantRepository tenants;

    @GET
    @Operation(summary = "List active roles for a tenant")
    @Transactional
    public List<RoleDto> listAll(@PathParam("tenantId") Long tenantId) {
        requireTenant(tenantId);
        return roles.listAll().stream().map(RoleDto::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get role by ID")
    @Transactional
    public RoleDto findById(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        return RoleDto.from(requireRole(tenantId, id));
    }

    @POST
    @Transactional
    @Operation(summary = "Create a role")
    public Response create(@PathParam("tenantId") Long tenantId, @Valid RoleCommands.Create cmd) {
        requireTenant(tenantId);
        roles.holderOfName(tenantId, cmd.name()).ifPresent(holder -> { throw nameTaken(holder); });

        UserLevel level = cmd.userLevel() != null
            ? UserLevel.fromCode(cmd.userLevel())
            : UserLevel.CLIENT_ORG;

        Role r = new Role(tenantId, cmd.name(), level);
        r.setDescription(cmd.description());
        roles.persist(r);

        return Response.created(UriBuilder.fromResource(RoleResource.class)
                .path("/{id}").build(tenantId, r.getId()))
            .entity(RoleDto.from(r))
            .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update role name and description")
    public RoleDto update(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id,
                          @Valid RoleCommands.Update cmd) {
        Role r = requireRole(tenantId, id);
        // Stripped as a create stores it: a padded rename onto a taken name is refused, onto its own
        // name it changes nothing — never a second role whose name differs only by spaces.
        String name = cmd.name().strip();
        if (!r.getName().equals(name))
            roles.holderOfName(tenantId, name).ifPresent(holder -> { throw nameTaken(holder); });
        r.setName(name);
        r.setDescription(cmd.description());
        return RoleDto.from(r);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Deactivate a role")
    public Response deactivate(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        requireRole(tenantId, id).archive();
        return Response.noContent().build();
    }

    private void requireTenant(Long tenantId) {
        Require.found(tenants, tenantId, "Tenant");
    }

    private Role requireRole(Long tenantId, Long id) {
        return Require.owned(roles, id, tenantId, "Role");
    }

    private static ClientErrorException nameTaken(Role holder) {
        return IdentityErrors.conflict(
            "role name " + holder.getName() + " is already held by role " + holder.getId());
    }
}
