package io.lumnik.identity.user;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.Require;
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

@Path("/api/tenants/{tenantId}/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Users")
@RequiresPermission("lm_admin")
public class UserResource {

    @Inject UserRepository users;
    @Inject TenantRepository tenants;

    @GET
    @Operation(summary = "List active users for a tenant")
    @Transactional
    public List<UserDto> listAll(@PathParam("tenantId") Long tenantId) {
        requireTenant(tenantId);
        return users.listAll().stream().map(UserDto::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get user by ID")
    @Transactional
    public UserDto findById(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        return UserDto.from(requireUser(tenantId, id));
    }

    @POST
    @Transactional
    @Operation(summary = "Create a user")
    public Response create(@PathParam("tenantId") Long tenantId, @Valid UserCommands.Create cmd) {
        requireTenant(tenantId);
        if (users.loginExists(cmd.login()))
            throw new ClientErrorException("Login already exists: " + cmd.login(), Response.Status.CONFLICT);

        User u = new User(tenantId, cmd.login(), cmd.name());
        u.setEmail(cmd.email());
        u.setDescription(cmd.description());
        users.persist(u);

        return Response.created(UriBuilder.fromResource(UserResource.class)
                .path("/{id}").build(tenantId, u.getId()))
            .entity(UserDto.from(u))
            .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update user profile")
    public UserDto update(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id,
                          @Valid UserCommands.Update cmd) {
        User u = requireUser(tenantId, id);
        u.setName(cmd.name());
        u.setEmail(cmd.email());
        u.setDescription(cmd.description());
        return UserDto.from(u);
    }

    @POST
    @Path("/{id}/unlock")
    @Transactional
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Unlock a locked user account")
    public UserDto unlock(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        User u = requireUser(tenantId, id);
        u.unlock();
        return UserDto.from(u);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Deactivate a user (archive — the row is kept)")
    public Response deactivate(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        requireUser(tenantId, id).archive();
        return Response.noContent().build();
    }

    private void requireTenant(Long tenantId) {
        Require.callerOwnsTenant(tenantId);
        Require.found(tenants, tenantId, "Tenant");
    }

    private User requireUser(Long tenantId, Long id) {
        Require.callerOwnsTenant(tenantId);
        return Require.owned(users, id, tenantId, "User");
    }
}
