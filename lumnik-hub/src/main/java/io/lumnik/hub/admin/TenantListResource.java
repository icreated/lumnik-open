package io.lumnik.hub.admin;

import io.lumnik.common.security.RequiresPermission;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/platform/admin/tenants")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_superadmin")
public class TenantListResource {

    @Inject EntityManager em;

    @GET
    public List<TenantSummary> list() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
            SELECT
                t.id, t.code, t.name,
                (SELECT count(*) FROM identity.org o
                    WHERE o.tenant_id = t.id AND o.deleted_at IS NULL),
                (SELECT count(*) FROM identity.app_user u
                    WHERE u.tenant_id = t.id AND u.deleted_at IS NULL)
            FROM identity.tenant t
            WHERE t.deleted_at IS NULL
            ORDER BY t.name
        """).getResultList();
        return rows.stream().map(r -> new TenantSummary(
            ((Number) r[0]).longValue(),
            (String) r[1],
            (String) r[2],
            ((Number) r[3]).longValue(),
            ((Number) r[4]).longValue()
        )).toList();
    }
}
