package io.lumnik.hub.connector.domain;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConnectorEndpointTest {

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Test
    void persist_and_retrieve_endpoint() throws Exception {
        var endpointId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("rest-fx-" + System.nanoTime());
            c.setTypeId("rest-generic");
            c.setConfig(Map.of());
            c.setEnabled(true);
            connectors.persist(c);

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("charges");
            ep.setMethod("GET");
            ep.setPath("/v1/charges");
            ep.setConfig(Map.of("pagination", Map.of("kind", "cursor")));
            ep.setEnabled(true);
            endpoints.persist(ep);
            utx.commit();
            return ep.getId();
        });

        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                ConnectorEndpoint loaded = endpoints.findById(endpointId);
                assertThat(loaded.getName()).isEqualTo("charges");
                assertThat(loaded.getMethod()).isEqualTo("GET");
                assertThat(loaded.getPath()).isEqualTo("/v1/charges");
                assertThat(loaded.isEnabled()).isTrue();
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void listByConnector_returns_active_endpoints() throws Exception {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("rest-lbc-" + System.nanoTime());
                c.setTypeId("rest-generic");
                c.setConfig(Map.of());
                c.setEnabled(true);
                connectors.persist(c);

                ConnectorEndpoint ep1 = new ConnectorEndpoint();
                ep1.setTenantId(1L);
                ep1.setConnectorId(c.getId());
                ep1.setName("ep-a");
                ep1.setMethod("GET");
                ep1.setPath("/a");
                ep1.setConfig(Map.of());
                ep1.setEnabled(true);
                endpoints.persist(ep1);

                ConnectorEndpoint ep2 = new ConnectorEndpoint();
                ep2.setTenantId(1L);
                ep2.setConnectorId(c.getId());
                ep2.setName("ep-b");
                ep2.setMethod("POST");
                ep2.setPath("/b");
                ep2.setConfig(Map.of());
                ep2.setEnabled(true);
                endpoints.persist(ep2);

                ConnectorEndpoint ep3 = new ConnectorEndpoint();
                ep3.setTenantId(1L);
                ep3.setConnectorId(c.getId());
                ep3.setName("ep-disabled");
                ep3.setMethod("GET");
                ep3.setPath("/disabled");
                ep3.setConfig(Map.of());
                ep3.setEnabled(false);
                endpoints.persist(ep3);
                utx.commit();

                utx.begin();
                var list = endpoints.listByConnector(c.getId());
                assertThat(list).hasSize(2);
                assertThat(list).extracting(ConnectorEndpoint::getName)
                        .containsExactlyInAnyOrder("ep-a", "ep-b")
                        .doesNotContain("ep-disabled");
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
