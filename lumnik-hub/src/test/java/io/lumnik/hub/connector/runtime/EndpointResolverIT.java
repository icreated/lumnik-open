package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EndpointResolverIT {

    @Inject EndpointResolver resolver;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Test
    void connector_with_two_endpoints_returns_both() {
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("two-ep-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setConfig(Map.of("url", "jdbc:x", "user", "u", "dialect", "postgres"));
            connectors.persist(c);
            connectors.flush();
            persistEndpoint(c.getId(), "t_a");
            persistEndpoint(c.getId(), "t_b");
            return c.getId();
        });

        List<UUID> ids = TenantContext.callAsSystem(() -> resolver.resolveEnabled(connectorId));
        assertThat(ids).hasSize(2);
    }

    @Test
    void flat_jdbc_connector_without_endpoints_lazily_creates_one_synthetic() {
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("flat-jdbc-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setConfig(Map.of(
                "url", "jdbc:postgresql://x/y", "user", "u", "dialect", "postgres",
                "table-schema", "public", "table-name", "src_demo",
                "mode", "full", "pk-columns", List.of("src_demo_id"), "chunk-size", 500));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        List<UUID> ids = TenantContext.callAsSystem(() -> resolver.resolveEnabled(connectorId));
        assertThat(ids).hasSize(1);

        ConnectorEndpoint ep = TenantContext.callAsSystem(() -> endpoints.findById(ids.get(0)));
        assertThat(ep.getName()).isEqualTo("src_demo");
        assertThat(ep.getConfig()).containsEntry("schema", "public");
        assertThat(ep.getConfig()).containsEntry("name", "src_demo");
        assertThat(ep.getConfig()).containsEntry("pk_columns", List.of("src_demo_id"));
        assertThat(ep.getConfig()).containsEntry("mode", "full");
    }

    /**
     * #2107: the first runs of a flat connector, submitted together, each found no endpoint and each created one; the
     * second insert met the unique name and its whole run failed. Every caller gets the one endpoint.
     */
    @Test
    void concurrent_first_resolutions_share_one_synthetic_endpoint() throws Exception {
        final int callers = 8;
        for (int round = 0; round < 5; round++) {
            UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("flat-race-" + System.nanoTime());
                c.setTypeId("hello-world");
                c.setConfig(Map.of("count", 1));
                connectors.persist(c);
                connectors.flush();
                return c.getId();
            });
            CyclicBarrier start = new CyclicBarrier(callers);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<List<UUID>>> resolved = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    resolved.add(pool.submit(() -> {
                        var requestContext = Arc.container().requestContext();
                        requestContext.activate();
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            return TenantContext.callAsSystem(() -> resolver.resolveEnabled(connectorId));
                        } finally {
                            requestContext.deactivate();
                        }
                    }));
                }
                Set<UUID> ids = new HashSet<>();
                for (Future<List<UUID>> f : resolved) {
                    List<UUID> one = f.get(30, TimeUnit.SECONDS);   // a lost insert used to throw here
                    assertThat(one).hasSize(1);
                    ids.addAll(one);
                }
                assertThat(ids).as("round %d: one endpoint for every caller", round).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void flat_hello_world_without_endpoints_creates_synthetic_carrying_count() {
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("flat-hello-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 1));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        List<UUID> ids = TenantContext.callAsSystem(() -> resolver.resolveEnabled(connectorId));
        assertThat(ids).hasSize(1);

        ConnectorEndpoint ep = TenantContext.callAsSystem(() -> endpoints.findById(ids.get(0)));
        assertThat(ep.getConfig()).containsEntry("count", 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void persistEndpoint(UUID connectorId, String name) {
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setTenantId(1L);
        ep.setConnectorId(connectorId);
        ep.setName(name);
        ep.setMethod("GET");
        ep.setPath("/" + name);
        ep.setEnabled(true);
        ep.setConfig(Map.of("schema", "public", "name", name, "mode", "full",
            "pk_columns", List.of(name + "_id")));
        endpoints.persist(ep);
        endpoints.flush();
    }
}
