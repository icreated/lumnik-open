package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ConnectorEndpointRepository implements PanacheRepositoryBase<ConnectorEndpoint, UUID> {

    public List<ConnectorEndpoint> listByConnector(UUID connectorId) {
        return list("connectorId = ?1 and deletedAt is null and enabled = true", connectorId);
    }

    public Optional<ConnectorEndpoint> findByName(UUID connectorId, String name) {
        return find("connectorId = ?1 and name = ?2 and deletedAt is null", connectorId, name)
                .firstResultOptional();
    }
}
