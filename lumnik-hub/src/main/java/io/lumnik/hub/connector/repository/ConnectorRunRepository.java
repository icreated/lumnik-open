package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ConnectorRunRepository implements PanacheRepositoryBase<ConnectorRun, UUID> {
  public List<ConnectorRun> findRecentByConnector(UUID connectorId, int limit) {
    return find("connectorId = ?1 and deletedAt is null order by startedAt desc", connectorId)
        .page(0, limit).list();
  }

  public Optional<ConnectorRun> findLatestByConnector(UUID connectorId) {
    return find("connectorId = ?1 and deletedAt is null order by startedAt desc", connectorId)
        .firstResultOptional();
  }
}
