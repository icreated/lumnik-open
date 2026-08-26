package io.lumnik.hub.connector.event;

import io.lumnik.hub.event.EventOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Thin wrapper that emits connector lifecycle events via the outbox.
 * EventOutboxService silently skips when no tenant is in context (system mode),
 * so callers should invoke this within a tenant context where persistence is desired.
 */
@ApplicationScoped
public class ConnectorEvents {

    private static final Logger LOG = Logger.getLogger(ConnectorEvents.class);

    @Inject
    EventOutboxService outbox;

    public void emit(String type, Map<String, Object> payload) {
        try {
            outbox.publish(type, payload);
        } catch (Exception e) {
            LOG.warnf("Failed to emit connector event %s: %s", type, e.getMessage());
        }
    }
}
