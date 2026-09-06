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

  /** All runs, newest first — the cross-connector ledger view. Sibling of {@link #findRecentByConnector};
   *  keeps the ordering idiom in the repository. (No deletedAt filter, preserving the ledger's existing
   *  set — the by-connector views hide soft-deleted runs, the ledger has always shown them.) */
  public List<ConnectorRun> findRecent(int limit) {
    return find("order by startedAt desc").page(0, limit).list();
  }
}
