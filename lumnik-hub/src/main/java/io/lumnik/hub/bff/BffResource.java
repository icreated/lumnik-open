package io.lumnik.hub.bff;

import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import io.lumnik.hub.edition.EditionMarker;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Set;

@Path("/api/bff/v1")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "BFF")
@RequiresPermission("lm_user")
public class BffResource {

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject ModuleManifestRegistry manifests;
    @Inject Instance<EditionMarker> editionMarker;

    @GET
    @Path("/me")
    @Operation(summary = "Current user context — user info, tenant, roles, and accessible modules")
    @Transactional
    public BffMeResponse me() {
        Long tenantId = requireTenantId();
        Long userId   = TenantContextFilter.currentUser();

        Tenant tenant = tenants.findByIdOptional(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));

        User user = userId != null
            ? users.findByIdOptional(userId)
                   .filter(u -> u.getTenantId().equals(tenantId))
                   .orElse(null)
            : null;

        Set<String> roles = TenantContextFilter.currentRoles();

        List<BffMeResponse.ModuleSummary> moduleList = manifests.all().stream()
            .map(BffMeResponse.ModuleSummary::from)
            .toList();

        return new BffMeResponse(
            user == null ? null : user.getId(),
            user == null ? "anonymous" : user.getLogin(),
            user == null ? tenant.getName() : user.getName(),
            user == null ? null : user.getEmail(),
            new BffMeResponse.TenantSummary(tenant.getId(), tenant.getCode(), tenant.getName()),
            roles,
            moduleList,
            editionOf(editionMarker)
        );
    }

    private Long requireTenantId() {
        Long tenantId = TenantContextFilter.current();
        if (tenantId == null)
            throw new BadRequestException("Missing tenant context (X-Tenant-ID header or JWT tenant_id claim)");
        return tenantId;
    }

    // Zero implementations on the classpath (the Open build excludes io/lumnik/hub/pro/**) is
    // exactly "open"; isUnsatisfied() names that directly rather than via isResolvable(), which
    // also folds in the ambiguous-count case this seam never has.
    static String editionOf(Instance<EditionMarker> editionMarker) {
        return editionMarker.isUnsatisfied() ? "open" : "commercial";
    }
}
