package io.lumnik.hub.hook;

import io.lumnik.hub.scheduler.TenantScope;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.BiFunction;

@ApplicationScoped
public class HookOverrideStore {

    private final BiFunction<Long, String, Boolean> testLoader;
    // Optional values: "no override for this tenant/hook" is a cacheable answer too,
    // and Caffeine does not admit null.
    private final Cache<String, Optional<Boolean>> cache;
    private BiFunction<Long, String, Boolean> activeLoader;

    /** CDI constructor. */
    public HookOverrideStore() {
        this(null, Duration.ofSeconds(60));
    }

    /** Test constructor — bypasses CDI, uses provided loader. */
    public HookOverrideStore(BiFunction<Long, String, Boolean> loader, Duration ttl) {
        this.testLoader = loader;
        this.activeLoader = loader;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    @Inject HookTenantOverrideRepository repo;

    @PostConstruct
    void init() {
        if (activeLoader == null) {
            activeLoader = (tenantId, hookId) -> TenantScope.get(tenantId,
                () -> repo.find(tenantId, hookId).map(HookTenantOverride::isEnabled).orElse(null));
        }
    }

    public boolean isEnabled(Long tenantId, String hookId, boolean defaultValue) {
        if (tenantId == null) return defaultValue;
        Optional<Boolean> override = cache.get(tenantId + "|" + hookId,
            key -> Optional.ofNullable(activeLoader.apply(tenantId, hookId)));
        return override.orElse(defaultValue);
    }

    public void invalidate(Long tenantId, String hookId) {
        cache.invalidate(tenantId + "|" + hookId);
    }

    @Transactional
    public void deleteOverride(Long tenantId, String hookId) {
        TenantScope.run(tenantId, () -> repo.find(tenantId, hookId).ifPresent(repo::delete));
        invalidate(tenantId, hookId);
    }

    @Transactional
    public void set(Long tenantId, String hookId, boolean enabled) {
        TenantScope.run(tenantId, () -> repo.find(tenantId, hookId)
            .ifPresentOrElse(e -> {
                e.setEnabled(enabled);
                e.setUpdatedAt(OffsetDateTime.now());
                repo.persist(e);
            }, () -> repo.persist(new HookTenantOverride(tenantId, hookId, enabled))));
        invalidate(tenantId, hookId);
    }
}
