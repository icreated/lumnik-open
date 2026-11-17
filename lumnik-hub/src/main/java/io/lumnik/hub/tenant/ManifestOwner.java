package io.lumnik.hub.tenant;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * Who reads an Entity, View or App manifest (#2050). The names are unique on the hub, but a manifest belongs to the
 * tenant it was applied from, and only that tenant reads it. One applied from the hub's own tenant (hubadmin) has no
 * owner and is shared, like a built-in app: every tenant reads it. A row written before owners existed is its tenant's
 * on a hub with one customer tenant, the built-in apps aside; shared otherwise (V99).
 */
public final class ManifestOwner {

  /** A row's {@code owner_tenant_id} read by the tenant bound to the placeholder. */
  public static final String READS = "(owner_tenant_id IS NULL OR owner_tenant_id = ?)";

  /** A row whose owner is the one bound ({@link #bind}, none included). */
  public static final String OWNED_BY = "owner_tenant_id IS NOT DISTINCT FROM ?";

  /** A row whose owner is another than the one bound. */
  public static final String OWNED_ELSEWHERE = "owner_tenant_id IS DISTINCT FROM ?";

  private ManifestOwner() {
  }

  /** The owner of a manifest applied from this tenant: none from the hub's own, the tenant itself otherwise. */
  public static Long of(long tenant) {
    return fromTheHub(tenant) ? null : tenant;
  }

  /** Whether this is the hub's own tenant: what it writes is shared. */
  public static boolean fromTheHub(Long tenant) {
    return tenant != null && tenant == DeploymentTenancy.HUBADMIN_TENANT;
  }

  /** Whether a manifest with this owner is the one this tenant writes: its own, or a shared one from the hub's. */
  public static boolean ownedBy(long tenant, Long owner) {
    return Objects.equals(owner, of(tenant));
  }

  /** Whether this tenant reads a manifest with this owner. */
  public static boolean reads(long tenant, Long owner) {
    return owner == null || owner == tenant;
  }

  /** Binds an owner, none included. */
  public static void bind(PreparedStatement ps, int index, Long owner) throws SQLException {
    ps.setObject(index, owner, Types.BIGINT);
  }
}
