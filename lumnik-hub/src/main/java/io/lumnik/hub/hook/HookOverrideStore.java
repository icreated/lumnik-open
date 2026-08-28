package io.lumnik.hub.hook;

import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

@ApplicationScoped
public class HookOverrideStore {

    private record CacheEntry(Boolean override, Instant expiresAt) {
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }

    private final BiFunction<Long, String, Boolean> testLoader;
    private final Duration ttl;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private BiFunction<Long, String, Boolean> activeLoader;

    /** CDI constructor. */
    public HookOverrideStore() {
        this.testLoader = null;
        this.ttl = Duration.ofSeconds(60);
    }

    /** Test constructor — bypasses CDI, uses provided loader. */
    public HookOverrideStore(BiFunction<Long, String, Boolean> loader, Duration ttl) {
        this.testLoader = loader;
        this.ttl = ttl;
        this.activeLoader = loader;
    }

    @Inject HookTenantOverrideRepository repo;

    @PostConstruct
    void init() {
        if (activeLoader == null) {
            activeLoader = (tenantId, hookId) -> {
                Long prev = TenantContextFilter.CURRENT_TENANT.get();
                try {
                    TenantContextFilter.CURRENT_TENANT.set(tenantId);
                    return repo.find(tenantId, hookId).map(HookTenantOverride::isEnabled).orElse(null);
                } finally {
                    if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
                    else TenantContextFilter.CURRENT_TENANT.set(prev);
                }
            };
        }
    }

    public boolean isEnabled(Long tenantId, String hookId, boolean defaultValue) {
        if (tenantId == null) return defaultValue;
        String key = tenantId + "|" + hookId;
        CacheEntry e = cache.get(key);
        if (e == null || e.expired()) {
            Boolean override = activeLoader.apply(tenantId, hookId);
            e = new CacheEntry(override, Instant.now().plus(ttl));
            cache.put(key, e);
        }
        return e.override() != null ? e.override() : defaultValue;
    }

    public void invalidate(Long tenantId, String hookId) {
        cache.remove(tenantId + "|" + hookId);
    }

    @Transactional
    public void setScript(Long tenantId, String hookId, String scriptContent) {
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            Optional<HookTenantOverride> existing = repo.find(tenantId, hookId);
            HookTenantOverride entity;
            if (existing.isPresent()) {
                entity = existing.get();
            } else {
                entity = new HookTenantOverride(tenantId, hookId, true);
            }
            entity.setScriptContent(scriptContent);
            entity.setEnabled(true);
            entity.setUpdatedAt(OffsetDateTime.now());
            repo.persist(entity);
        } finally {
            if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
            else TenantContextFilter.CURRENT_TENANT.set(prev);
        }
        invalidate(tenantId, hookId);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> findScript(Long tenantId, String hookId) {
        if (tenantId == null) return Optional.empty();
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            return repo.find(tenantId, hookId)
                .map(HookTenantOverride::getScriptContent)
                .filter(s -> s != null && !s.isBlank());
        } finally {
            if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
            else TenantContextFilter.CURRENT_TENANT.set(prev);
        }
    }

    @Transactional
    public void deleteOverride(Long tenantId, String hookId) {
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            repo.find(tenantId, hookId).ifPresent(repo::delete);
        } finally {
            if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
            else TenantContextFilter.CURRENT_TENANT.set(prev);
        }
        invalidate(tenantId, hookId);
    }

    @Transactional
    public void set(Long tenantId, String hookId, boolean enabled) {
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            Optional<HookTenantOverride> existing = repo.find(tenantId, hookId);
            if (existing.isPresent()) {
                HookTenantOverride e = existing.get();
                e.setEnabled(enabled);
                e.setUpdatedAt(OffsetDateTime.now());
                repo.persist(e);
            } else {
                repo.persist(new HookTenantOverride(tenantId, hookId, enabled));
            }
        } finally {
            if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
            else TenantContextFilter.CURRENT_TENANT.set(prev);
        }
        invalidate(tenantId, hookId);
    }
}
