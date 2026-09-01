package io.lumnik.hub.hook;

import io.lumnik.hub.tenant.TenantContextFilter;
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
        Optional<Boolean> override = cache.get(tenantId + "|" + hookId,
            key -> Optional.ofNullable(activeLoader.apply(tenantId, hookId)));
        return override.orElse(defaultValue);
    }

    public void invalidate(Long tenantId, String hookId) {
        cache.invalidate(tenantId + "|" + hookId);
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
