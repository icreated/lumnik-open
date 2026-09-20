package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.Connector;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConnectorRepository implements PanacheRepositoryBase<Connector, UUID> {

  /**
   * Connector names for a page of ids, one query — the admin ledgers (runs, dead letters) are
   * read by humans and used to speak only in UUIDs. A projection, not entities: a name lookup
   * has no business hydrating each connector's JSON config. An id with no connector is absent.
   */
  public Map<UUID, String> namesById(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return getEntityManager()
        .createQuery("select c.id, c.name from Connector c where c.id in :ids", Object[].class)
        .setParameter("ids", ids)
        .getResultStream()
        .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
  }
  /**
   * This tenant's connector of that name, HELD for the rest of the transaction — the ONE place the
   * (tenantId, name) address is written.
   *
   * <p>It used to be spelled out twice, here and in {@code ApplyUpsert.connector}, a few lines apart
   * in the same request: the scope guard read the row through one copy and the upsert wrote it
   * through the other. Three sibling repositories carry that same shape PLUS a
   * {@code deletedAt IS NULL} clause, so the day connectors get soft-delete, one copy gets the
   * clause and the other does not — and the copy that misses it is the guard.
   *
   * <p><b>The lock is what makes the guard's answer still true when the write happens</b> (#585).
   * {@code ConnectorApplyResource.apply} is {@code @Transactional}, so the check and the write
   * already share one transaction — and that was never enough. Under READ COMMITTED a plain
   * {@code SELECT} takes nothing, {@code Connector} has no {@code @Version}, and the
   * {@code UPDATE … SET tags} Hibernate dirty-checks at flush re-checks nothing: a concurrent apply
   * committing in between is silently overwritten, and the authorization that allowed the write was
   * decided on a state that no longer exists. That is the same cross-métier re-home
   * {@code ApplyScopeBoundary} exists to refuse (#509), reached through the gap rather than through
   * the door. {@code PESSIMISTIC_WRITE} closes it: the concurrent writer waits for this transaction
   * instead of racing it.
   *
   * <p><b>Both callers are the write path</b>, which is why the lock lives here rather than in a
   * separate locked sibling the apply door would have to remember to call — the door's guard and
   * {@code ApplyUpsert}'s own read, one after the other in the same request. Taking it twice is
   * free; the second is already the holder. A future READ path must NOT reuse this method: it would
   * queue readers behind writers for nothing. Give it an unlocked sibling, and keep the address
   * itself in one place.
   *
   * <p>A name with no row takes no lock — there is nothing to hold. The create race is the
   * {@code (tenant_id, name)} unique constraint's job: a second insert trips 23505, which is loud,
   * not a silent re-home.
   */
  public Optional<Connector> byAddressForUpdate(long tenant, String name) {
    return find("tenantId = ?1 and name = ?2", tenant, name)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .firstResultOptional();
  }

  /**
   * The scope tags stored on this tenant's connector of that name, or empty when the name is free —
   * the apply door's re-homing check ({@code ApplyScopeBoundary}). Asked before the write rather
   * than after, so the answer is about the row the upsert is ABOUT to overwrite, and the row is
   * HELD from here until the door's transaction ends, so "about to" stays true.
   *
   * <p>Named for the lock because this is the line where the authorization decision is taken: a
   * reader of {@code ConnectorApplyResource} should not have to open this file to learn that the
   * answer is guaranteed rather than merely current.
   *
   * <p>An empty list is a real answer (a scopeless connector), distinct from an absent row.
   */
  public Optional<List<String>> lockedScopesOf(long tenant, String name) {
    return byAddressForUpdate(tenant, name)
        .map(c -> c.getTags() == null ? List.of() : c.getTags());
  }
}
