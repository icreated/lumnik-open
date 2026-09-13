package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * Endpoint names for a page of ids, one query — the twin of {@code ConnectorRepository.namesById},
     * written for the same reason; {@code RunAdminDto.from} states what the ledger does with them.
     * A projection, not entities: a name lookup has no business hydrating each endpoint's JSON
     * config. An id with no endpoint is absent.
     *
     * <p>Soft-deleted endpoints ARE resolved, deliberately — a run that read a table someone has since
     * removed still read it, and naming it is the whole point of the ledger. A HARD delete nulls the
     * run's {@code endpoint_id} instead ({@code ON DELETE SET NULL}, V13), so there is no id left to
     * resolve and the ledger simply says nothing.
     */
    public Map<UUID, String> namesById(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return getEntityManager()
                .createQuery("select e.id, e.name from ConnectorEndpoint e where e.id in :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultStream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
    }

    public Optional<ConnectorEndpoint> findByName(UUID connectorId, String name) {
        return find("connectorId = ?1 and name = ?2 and deletedAt is null", connectorId, name)
                .firstResultOptional();
    }
}
