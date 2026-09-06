package io.lumnik.identity.roleorgaccess;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.Require;
import io.lumnik.identity.org.OrgRepository;
import io.lumnik.identity.org.Organization;
import io.lumnik.identity.role.Role;
import io.lumnik.identity.role.RoleRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/tenants/{tenantId}/roles/{roleId}/orgs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
@Tag(name = "Role Org Access")
@RequiresPermission("lm_admin")
public class RoleOrgAccessResource {

    @Inject RoleOrgAccessRepository grants;
    @Inject RoleRepository roles;
    @Inject OrgRepository orgs;

    @GET
    @Operation(summary = "List active org grants for a role")
    @Transactional
    public List<RoleOrgAccessDto> listAll(@PathParam("tenantId") Long tenantId,
                                          @PathParam("roleId") Long roleId) {
        requireRole(tenantId, roleId);
        return grants.listByRole(roleId).stream().map(RoleOrgAccessDto::from).toList();
    }

    @POST
    @Path("/{orgId}")
    @Transactional
    @Operation(summary = "Grant a role access to an org")
    public Response grant(@PathParam("tenantId") Long tenantId,
                          @PathParam("roleId") Long roleId,
                          @PathParam("orgId") Long orgId,
                          @QueryParam("readOnly") @DefaultValue("false") boolean readOnly) {
        requireRole(tenantId, roleId);
        requireOrg(tenantId, orgId);

        if (grants.grantExists(roleId, orgId))
            throw new ClientErrorException("Org access already granted", Response.Status.CONFLICT);

        RoleOrgAccess grant = new RoleOrgAccess(tenantId, roleId, orgId, readOnly);
        grants.persist(grant);
        return Response.status(Response.Status.CREATED).entity(RoleOrgAccessDto.from(grant)).build();
    }

    @DELETE
    @Path("/{orgId}")
    @Transactional
    @Operation(summary = "Revoke role access to an org")
    public Response revoke(@PathParam("tenantId") Long tenantId,
                           @PathParam("roleId") Long roleId,
                           @PathParam("orgId") Long orgId) {
        requireRole(tenantId, roleId);
        RoleOrgAccess grant = grants.findGrant(roleId, orgId)
            .orElseThrow(() -> new NotFoundException("Org access grant not found"));
        if (!grant.getTenantId().equals(tenantId))
            throw new NotFoundException("Org access grant not found");
        grant.setActive(false);
        return Response.noContent().build();
    }

    private Role requireRole(Long tenantId, Long roleId) {
        Require.callerOwnsTenant(tenantId);
        return Require.owned(roles, roleId, tenantId, "Role");
    }

    private Organization requireOrg(Long tenantId, Long orgId) {
        Require.callerOwnsTenant(tenantId);
        return Require.owned(orgs, orgId, tenantId, "Organization");
    }
}
