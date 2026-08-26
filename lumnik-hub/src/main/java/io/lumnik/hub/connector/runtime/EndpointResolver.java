package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the set of enabled endpoint ids the engine should run for a connector.
 *
 * <p>If the connector has at least one (enabled) endpoint, those ids are returned.
 * If it has none, a synthetic endpoint is created lazily — in its own new transaction —
 * via {@link FlatConnectorEndpointMigration#ensureSyntheticEndpoint(Connector)}, so a flat
 * connector created before the unified engine (or one whose startup migration was skipped
 * because it is table-less) becomes runnable on first trigger.
 *
 * <p>Callers must already be on a tenant/system context appropriate for the lookups.
 */
@ApplicationScoped
public class EndpointResolver {

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject FlatConnectorEndpointMigration migration;

    /**
     * Returns the enabled endpoint ids of a connector. If none exist, lazily creates ONE
     * synthetic endpoint and returns its id.
     *
     * <p>Both blocks run in their own {@code REQUIRES_NEW} transactions so the Agroal pool
     * interceptor fires and sets the tenant GUC even when the caller has no active JTA
     * transaction (e.g. the admin resource {@code runNow} and the scheduler tick).
     * The second block also performs the synthetic-endpoint write.
     * This is required for correctness under an RLS-enforced datasource (lumnik_app) where the
     * GUC must be set before any table scan — omitting the transaction left the GUC unset and
     * returned zero endpoints (finding lm-connector-run-rls-404, follow-on 422).
     */
    public List<UUID> resolveEnabled(UUID connectorId) {
        // Run inside a transaction so the Agroal interceptor fires and sets the tenant GUC.
        List<ConnectorEndpoint> enabled = QuarkusTransaction.requiringNew()
                .call(() -> endpoints.listByConnector(connectorId));
        if (!enabled.isEmpty()) {
            return enabled.stream().map(ConnectorEndpoint::getId).toList();
        }
        // No endpoints — lazily create a synthetic one in its own transaction.
        UUID syntheticId = QuarkusTransaction.requiringNew().call(() -> {
            Connector c = connectors.findById(connectorId);
            if (c == null) return null;
            ConnectorEndpoint ep = migration.ensureSyntheticEndpoint(c);
            return ep.getId();
        });
        return syntheticId == null ? List.of() : List.of(syntheticId);
    }
}
