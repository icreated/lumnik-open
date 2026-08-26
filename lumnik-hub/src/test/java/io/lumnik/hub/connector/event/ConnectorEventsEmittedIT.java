package io.lumnik.hub.connector.event;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConnectorEventsEmittedIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void completed_run_emits_started_then_completed() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L);
                c.setName("events-" + System.nanoTime());
                c.setTypeId("hello-world");
                c.setConfig(Map.of("count", 3));
                connectors.persist(c);
                connectors.flush();
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        engine.runSync(connectorId, new RunTrigger.Manual(1L));

        List<String> types = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                @SuppressWarnings("unchecked")
                List<String> ts = em.createNativeQuery(
                    "select event_type from core.event_outbox where event_type like 'connector.%' order by created_at"
                ).getResultList();
                utx.commit();
                return ts;
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertThat(types).contains("connector.run.started", "connector.run.completed");
    }
}
