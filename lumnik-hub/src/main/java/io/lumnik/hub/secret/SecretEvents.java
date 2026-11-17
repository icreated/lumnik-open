package io.lumnik.hub.secret;

import io.lumnik.hub.event.EventOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits platform secret lifecycle events to the outbox for SOC 2 audit trail.
 * Topics: platform.secret.created / updated / scoped / deleted / read.
 *
 * <p>Silently swallows emit errors — a failed audit event must not roll back a
 * business mutation. The caller's transaction is NOT affected.
 */
@ApplicationScoped
public class SecretEvents {

    private static final Logger LOG = Logger.getLogger(SecretEvents.class);

    @Inject
    EventOutboxService outbox;

    public void emitCreated(long tenantId, String name, Long by) {
        emit("platform.secret.created", name, by, Map.of());
    }

    public void emitUpdated(long tenantId, String name, Long by, Instant previousRotatedAt) {
        emit("platform.secret.updated", name, by,
            Map.of("previousRotatedAt", previousRotatedAt == null ? "null" : previousRotatedAt.toString()));
    }

    /** The scopes a secret serves changed (#2015), comma-separated as {@code lm secret scope} takes them. */
    public void emitScoped(long tenantId, String name, Long by, List<String> previousScopes, List<String> scopes) {
        emit("platform.secret.scoped", name, by,
            Map.of("previousScopes", String.join(",", previousScopes), "scopes", String.join(",", scopes)));
    }

    public void emitDeleted(long tenantId, String name, Long by) {
        emit("platform.secret.deleted", name, by, Map.of());
    }

    /** Whether the read was recorded: the resolver audits a reader again until one is (#2015). */
    public boolean emitRead(long tenantId, String name, Long by) {
        return emit("platform.secret.read", name, by, Map.of());
    }

    private boolean emit(String topic, String name, Long by, Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new HashMap<>(extra);
            payload.put("name", name);
            payload.put("by", by == null ? "system" : by);
            payload.put("at", Instant.now().toString());
            return outbox.publish(topic, payload);
        } catch (Exception e) {
            LOG.warnf("Failed to emit secret event %s: %s", topic, e.getMessage());
            return false;
        }
    }
}
