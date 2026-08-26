package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class ConnectorCursorEntityRepository implements PanacheRepositoryBase<ConnectorCursorEntity, UUID> {

  /** Find the cursor row associated with a given endpoint id. */
  public ConnectorCursorEntity findByEndpoint(UUID endpointId) {
    return find("endpointId", endpointId).firstResult();
  }
}
