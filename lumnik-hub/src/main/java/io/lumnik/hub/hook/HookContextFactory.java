package io.lumnik.hub.hook;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.hub.webhook.OutboxPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class HookContextFactory {

    private static final Logger LOG = Logger.getLogger("io.lumnik.hub.hook");

    @Inject OutboxPublisher outbox;
    @Inject EntityManager em;

    public HookContext create(HookBinding binding) {
        Long tenantId = TenantContextFilter.CURRENT_TENANT.get();
        Long userId   = TenantContextFilter.CURRENT_USER.get();
        Set<String> roles = CurrentRolesHolder.get();
        // TODO: populate name from JWT claim when Keycloak is wired
        UserContext user = new UserContext(userId, "current", roles);
        Instant createdAt = Instant.now();

        return new HookContext() {
            @Override public Long tenantId() { return tenantId; }
            @Override public Long userId()   { return userId; }
            @Override public UserContext user() { return user; }
            @Override public String module() { return binding.module(); }

            @Override public void fail(String code, String message) {
                throw new HookCancellation(code, message, Map.of());
            }
            @Override public void fail(String code, String message, Map<String,Object> details) {
                throw new HookCancellation(code, message, details);
            }

            @Override public void event(String type, Object payload) {
                outbox.publish(tenantId, type, payload);
            }

            @Override public <E> Optional<E> lookup(Class<E> type, Long id) {
                return Optional.ofNullable(em.find(type, id));
            }

            @Override public void log(String message) { log(message, Map.of()); }
            @Override public void log(String message, Map<String,Object> fields) {
                Map<String,Object> mdc = new HashMap<>(fields);
                mdc.put("hookId", binding.id());
                mdc.put("module", binding.module());
                mdc.put("tenantId", tenantId);
                LOG.infof("[hook=%s tenant=%s] %s %s", binding.id(), tenantId, message, mdc);
            }

            @Override public String hookId() { return binding.id(); }
            @Override public Instant timestamp() { return createdAt; }
        };
    }
}
