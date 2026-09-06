package io.lumnik.identity.userrole;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.Require;
import io.lumnik.identity.role.Role;
import io.lumnik.identity.role.RoleRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/tenants/{tenantId}/users/{userId}/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
@Tag(name = "User Roles")
@RequiresPermission("lm_admin")
public class UserRoleResource {

    @Inject UserRoleRepository userRoles;
    @Inject UserRepository users;
    @Inject RoleRepository roles;

    @GET
    @Operation(summary = "List active role assignments for a user")
    @Transactional
    public List<UserRoleDto> listAll(@PathParam("tenantId") Long tenantId,
                                     @PathParam("userId") Long userId) {
        requireUser(tenantId, userId);
        return userRoles.listByUser(userId).stream().map(UserRoleDto::from).toList();
    }

    @POST
    @Path("/{roleId}")
    @Transactional
    @Operation(summary = "Assign a role to a user")
    public Response assign(@PathParam("tenantId") Long tenantId,
                           @PathParam("userId") Long userId,
                           @PathParam("roleId") Long roleId) {
        requireUser(tenantId, userId);
        requireRole(tenantId, roleId);

        if (userRoles.assignmentExists(userId, roleId))
            throw new ClientErrorException("Role already assigned", Response.Status.CONFLICT);

        UserRole ur = new UserRole(tenantId, userId, roleId);
        userRoles.persist(ur);
        return Response.status(Response.Status.CREATED).entity(UserRoleDto.from(ur)).build();
    }

    @DELETE
    @Path("/{roleId}")
    @Transactional
    @Operation(summary = "Remove a role from a user")
    public Response remove(@PathParam("tenantId") Long tenantId,
                           @PathParam("userId") Long userId,
                           @PathParam("roleId") Long roleId) {
        requireUser(tenantId, userId);
        UserRole ur = userRoles.findAssignment(userId, roleId)
            .orElseThrow(() -> new NotFoundException("Role assignment not found"));
        if (!ur.getTenantId().equals(tenantId))
            throw new NotFoundException("Role assignment not found");
        ur.setActive(false);
        return Response.noContent().build();
    }

    private User requireUser(Long tenantId, Long userId) {
        Require.callerOwnsTenant(tenantId);
        return Require.owned(users, userId, tenantId, "User");
    }

    private Role requireRole(Long tenantId, Long roleId) {
        Require.callerOwnsTenant(tenantId);
        return Require.owned(roles, roleId, tenantId, "Role");
    }
}
