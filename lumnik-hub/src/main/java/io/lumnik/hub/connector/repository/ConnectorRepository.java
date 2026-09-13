package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.Connector;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

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
   * This tenant's connector of that name — the ONE place the (tenantId, name) address is written.
   *
   * <p>It used to be spelled out twice, here and in {@code ApplyUpsert.connector}, a few lines apart
   * in the same request: the scope guard read the row through one copy and the upsert wrote it
   * through the other. Three sibling repositories carry that same shape PLUS a
   * {@code deletedAt IS NULL} clause, so the day connectors get soft-delete, one copy gets the
   * clause and the other does not — and the copy that misses it is the guard.
   */
  public Optional<Connector> byAddress(long tenant, String name) {
    return find("tenantId = ?1 and name = ?2", tenant, name).firstResultOptional();
  }

  /**
   * The scope tags stored on this tenant's connector of that name, or empty when the name is free —
   * the apply door's re-homing check ({@code ApplyScopeBoundary}). Asked before the write rather
   * than after, so the answer is about the row the upsert is ABOUT to overwrite.
   *
   * <p>An empty list is a real answer (a scopeless connector), distinct from an absent row.
   */
  public Optional<List<String>> scopesOf(long tenant, String name) {
    return byAddress(tenant, name).map(c -> c.getTags() == null ? List.of() : c.getTags());
  }
}
