package io.lumnik.hub.api.connector;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import java.util.UUID;

/**
 * A connector lives in the scopes its tags name (#1208), and every door acting on one by id can
 * change, run or delete it — so each asks the write rule, {@link ScopeGrants#allowsAll}: a bound
 * caller must hold every tag, and a scopeless connector is out of their reach. An unbound caller
 * administers the whole tenant.
 *
 * <p>The doors that reach a connector through what it left behind — its runs, its dead letters,
 * its source — ask the same rule (#1212).
 *
 * <p>{@link #held} and {@link #refuseUnlessHolds} refuse by THROWING the uniform
 * {@code 403 scope not granted}, as {@code ApplyScopeBoundary} does: a refusal handed back could be
 * dropped, and calling one of these is then acting on it.
 */
public final class ConnectorScope {

  private ConnectorScope() {}

  /**
   * The tenant's connector under {@code id}, null when it has none, so the door's 404 comes first,
   * as on the other read doors; 403 thrown when the caller does not hold all its tags.
   *
   * <p>The tenant is the query's own predicate, never {@code findById}: an id lookup bypasses the
   * tenant {@code @Filter}, and RLS alone then stands between a caller and another tenant's
   * connector (#1216 review).
   */
  public static Connector held(ConnectorRepository repo, UUID id) {
    Connector c = repo.findByIdForTenant(id, TenantContextFilter.current());
    if (c != null) refuseUnlessHolds(c.getTags());
    return c;
  }

  /**
   * {@link #held}, with the row HELD until the door's transaction ends (#1217) — what every door
   * that writes the connector or its children calls, so the decision is still true when the write
   * commits. Under READ COMMITTED a plain read takes nothing: a retag committing between the
   * decision and the flush was judged stale, and the flush — which rewrites every column — put the
   * old tags back over it. The lock is {@code FOR NO KEY UPDATE} on PostgreSQL: it queues the
   * connector's other writers, never a run inserting rows that reference the connector.
   *
   * <p>Not for {@code run} — see {@code ConnectorAdminResource.runNow}.
   */
  public static Connector heldForUpdate(ConnectorRepository repo, UUID id) {
    Connector c = repo.findByIdForTenantForUpdate(id, TenantContextFilter.current());
    if (c != null) refuseUnlessHolds(c.getTags());
    return c;
  }

  /** 403 thrown unless the caller holds every one of these tags — creating or retagging into them. */
  public static void refuseUnlessHolds(List<String> tags) {
    if (!ScopeGrants.allowsAll(tags)) throw new WebApplicationException(ApiErrors.scopeNotGranted());
  }

  /** Does the caller hold every tag of this connector? The rule, for a list filtering itself. */
  public static boolean holds(Connector c) {
    return ScopeGrants.allowsAll(c.getTags());
  }

  /**
   * The ids of the connectors a bound caller holds, null for an unbound caller (no
   * restriction). A list narrows its QUERY to them, never the page it got back: filtered after the
   * fact, a page of the tenant's newest N comes back short or empty while the caller's own rows sit
   * further down. Soft-deleted ones included: the run ledger shows their runs, as {@link #held} does.
   * Ids and tags only — a list refresh has no use for the connectors' configs.
   */
  public static List<UUID> heldIds(ConnectorRepository repo) {
    if (ScopeGrants.granted().isEmpty()) return null;
    return repo.getEntityManager()
        .createQuery("select c.id, c.tags from Connector c", Object[].class)
        .getResultStream()
        .filter(row -> ScopeGrants.allowsAll(tags(row[1])))
        .map(row -> (UUID) row[0])
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<String> tags(Object column) {
    return (List<String>) column;
  }
}
