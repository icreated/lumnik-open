package io.lumnik.hub.admin;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * The superadmin's cross-tenant tenant list, read on the {@code admin} datasource.
 *
 * <p>It used the default one, and an annotation would not have been enough. The outer query reads
 * {@code identity.tenant}, which carries no RLS policy, so the NAMES always came back; the two
 * subqueries count {@code identity.org} and {@code identity.app_user}, which do. Every tenant
 * except the caller's own therefore reported <b>0 orgs and 0 users</b> — a plausible-looking
 * answer, which is worse than a blank one, on the page whose whole job is to compare tenants.
 *
 * <p>System mode would not have fixed it either. The cross-tenant sentinel {@code '0'} is honoured
 * by {@code core.event_outbox} (V4/V24) and by the {@code connector.*} policies (V22), and by no
 * identity policy at all — so there is no GUC value that makes this query correct on the
 * RLS-enforced datasource. A cross-tenant read belongs on the datasource that is allowed to be
 * cross-tenant, which is what {@code admin} (BYPASSRLS) is for.
 *
 * <p>No {@code @Transactional} here, deliberately: the admin datasource has JTA enlistment
 * disabled and needs no {@code SET LOCAL} to see rows. The annotation is the fix for the doors
 * that read tenant-scoped data as the tenant; this one is not one of them.
 */
@Path("/api/platform/admin/tenants")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_superadmin")
public class TenantListResource {

    private static final String LIST_SQL = """
        SELECT
            t.id, t.code, t.name,
            (SELECT count(*) FROM identity.org o
                WHERE o.tenant_id = t.id AND o.deleted_at IS NULL),
            (SELECT count(*) FROM identity.app_user u
                WHERE u.tenant_id = t.id AND u.deleted_at IS NULL)
        FROM identity.tenant t
        WHERE t.deleted_at IS NULL
        ORDER BY t.name
    """;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @GET
    public List<TenantSummary> list() {
        return Jdbc.query(adminDs, "TenantListResource.list", LIST_SQL, ps -> { },
            rs -> new TenantSummary(rs.getLong(1), rs.getString(2), rs.getString(3),
                                    rs.getLong(4), rs.getLong(5)));
    }
}
