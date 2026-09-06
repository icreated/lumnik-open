package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.Connector;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
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
}
