package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ConnectorEndpointRepository implements PanacheRepositoryBase<ConnectorEndpoint, UUID> {

    /** Enabled endpoints only — the runtime fan-out (a dormant endpoint must not run). */
    public List<ConnectorEndpoint> listByConnector(UUID connectorId) {
        return list("connectorId = ?1 and deletedAt is null and enabled = true", connectorId);
    }

    /** Every live endpoint, enabled or dormant — the API view. A one-shot's endpoint is dormant
     *  (enabled=false) but still real: it must be listable so the operator (and {@code lm endpoint
     *  purge}) can see and address it. The runtime keeps {@link #listByConnector} (enabled only). */
    public List<ConnectorEndpoint> listAllByConnector(UUID connectorId) {
        return list("connectorId = ?1 and deletedAt is null", connectorId);
    }

    public Optional<ConnectorEndpoint> findByName(UUID connectorId, String name) {
        return find("connectorId = ?1 and name = ?2 and deletedAt is null", connectorId, name)
                .firstResultOptional();
    }
}
