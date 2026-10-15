package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.ProjectionFieldMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Minimal, convention-based in-memory {@link HubSearchContractDriver} double: a row is visible to a
 *  user unless the user holds at least one {@code scope:*} grant, in which case it must include
 *  {@code "scope:" + entity.name()} — mirrors "zero scope roles = unscoped, tenant-wide; one or more
 *  = bound" exactly, without needing this interface to expose real manifest structure. Exists only to
 *  exercise {@link ProviderContractHarness#hubLane} in isolation; the real end-to-end proof against
 *  actual {@code ScopeGrants} is {@code RestHubSearchContractDriver} (Tasks 11-13), run through
 *  {@code SearchProviderContractIT} (Task 14). */
class FakeHubSearchContractDriver implements HubSearchContractDriver {
    private record Key(long tenantId, String entity, String rowKey) {}

    private final Map<Key, Map<String, String>> rows = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> allowedFieldsByEntity = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userScopes = new ConcurrentHashMap<>();
    private final AtomicLong tenantSeq = new AtomicLong();
    private final AtomicLong userSeq = new AtomicLong();
    private final AtomicLong rebuildSeq = new AtomicLong();

    @Override
    public TenantHandle provisionTenant() {
        return new TenantHandle(tenantSeq.incrementAndGet());
    }

    @Override
    public UserHandle provisionUser(TenantHandle tenant, Set<String> scopeTags) {
        long id = userSeq.incrementAndGet();
        userScopes.put(id, new CopyOnWriteArraySet<>(scopeTags));
        return new UserHandle("user-" + id, tenant.tenantId());
    }

    private static long userId(UserHandle user) {
        return Long.parseLong(user.userId().substring("user-".length()));
    }

    @Override
    public void grantScope(TenantHandle tenant, UserHandle user, String scopeTag) {
        userScopes.computeIfAbsent(userId(user), k -> new CopyOnWriteArraySet<>()).add(scopeTag);
    }

    @Override
    public void revokeScope(TenantHandle tenant, UserHandle user, String scopeTag) {
        userScopes.getOrDefault(userId(user), Set.of()).remove(scopeTag);
    }

    @Override
    public void ingestCanonicalRows(TenantHandle tenant, CanonicalEntityDef entity, List<CanonicalRow> canonicalRows) {
        for (CanonicalRow row : canonicalRows) {
            rows.put(new Key(tenant.tenantId(), entity.name(), row.rowKey()), new HashMap<>(row.values()));
        }
    }

    @Override
    public void deleteCanonicalRow(TenantHandle tenant, CanonicalEntityDef entity, String rowKey) {
        rows.remove(new Key(tenant.tenantId(), entity.name(), rowKey));
    }

    @Override
    public void applySearchIndex(TenantHandle tenant, String indexName, CanonicalEntityDef entity, List<CanonicalFieldDef> fields) {
        allowedFieldsByEntity.put(entity.name(),
            fields.stream().map(CanonicalFieldDef::name).collect(Collectors.toSet()));
    }

    @Override
    public void reapplyEntity(TenantHandle tenant, CanonicalEntityDef updated) {
        allowedFieldsByEntity.computeIfPresent(updated.name(), (k, v) -> {
            Set<String> stillDeclared = updated.columns().stream()
                .map(CanonicalFieldDef::name).collect(Collectors.toSet());
            v.retainAll(stillDeclared);
            return v;
        });
    }

    /** Simple fake: a fresh entity with two rows sharing the same value on their one canonical field
     *  is "independently resolved" enough for this in-memory model — {@link #mergeResolvableScenario}
     *  already collapses them by rowKey, no crosswalk needed. */
    @Override
    public ResolvableMergeScenario prepareResolvableMergeScenario(TenantHandle tenant, String indexName, String probeText) {
        CanonicalEntityDef entity = new CanonicalEntityDef(
            "resolvable-merge-" + tenant.tenantId(),
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        UserHandle user = provisionUser(tenant, Set.of());
        applySearchIndex(tenant, indexName, entity, entity.columns());
        ingestCanonicalRows(tenant, entity, List.of(
            new CanonicalRow("rm-1", Map.of("probe", probeText)),
            new CanonicalRow("rm-2", Map.of("probe", probeText))));
        return new ResolvableMergeScenario(entity, user, "rm-1", "rm-2");
    }

    @Override
    public void mergeResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario) {
        Map<String, String> fromValues = rows.remove(
            new Key(tenant.tenantId(), scenario.entity().name(), scenario.fromRowKey()));
        if (fromValues != null) {
            rows.merge(new Key(tenant.tenantId(), scenario.entity().name(), scenario.intoRowKey()), fromValues, (a, b) -> a);
        }
    }

    @Override
    public void splitResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario) {
        Map<String, String> intoValues = rows.get(
            new Key(tenant.tenantId(), scenario.entity().name(), scenario.intoRowKey()));
        if (intoValues != null) {
            rows.put(new Key(tenant.tenantId(), scenario.entity().name(), scenario.fromRowKey()), new HashMap<>(intoValues));
        }
    }

    @Override
    public void deleteTenant(TenantHandle tenant) {
        rows.keySet().removeIf(k -> k.tenantId() == tenant.tenantId());
    }

    @Override
    public void awaitProjectionConvergence(TenantHandle tenant, String indexName, Duration timeout) {
        // fake: every write above is already synchronously visible — nothing to await
    }

    @Override
    public void awaitOneProjectionCycle(TenantHandle tenant, String indexName) {
        // fake: every write above is already synchronously visible — nothing to tick
    }

    @Override
    public CanonicalSearchResult query(TenantHandle tenant, UserHandle asUser, String text, Map<String, List<String>> facets) {
        Set<String> scopes = userScopes.getOrDefault(userId(asUser), Set.of());
        List<CanonicalSearchHit> hits = new ArrayList<>();
        for (Map.Entry<Key, Map<String, String>> e : rows.entrySet()) {
            Key key = e.getKey();
            if (key.tenantId() != tenant.tenantId()) continue;
            if (!scopes.isEmpty() && !scopes.contains("scope:" + key.entity())) continue;

            Set<String> allowed = allowedFieldsByEntity.getOrDefault(key.entity(), Set.of());
            Map<String, String> visibleFields = new HashMap<>();
            for (Map.Entry<String, String> fv : e.getValue().entrySet()) {
                if (allowed.contains(fv.getKey())) visibleFields.put(fv.getKey(), fv.getValue());
            }
            boolean matches = visibleFields.values().stream().anyMatch(v -> v != null && v.contains(text));
            if (matches) {
                hits.add(new CanonicalSearchHit(key.entity(), Map.of("rowKey", key.rowKey()), visibleFields,
                    Map.of(), "/api/entities/" + key.entity() + "?rowKey=" + key.rowKey()));
            }
        }
        return new CanonicalSearchResult(hits, Map.of(), false, null);
    }

    @Override
    public RebuildOutcome triggerRebuild(TenantHandle tenant, String indexName) {
        return new RebuildOutcome.Started("run-" + rebuildSeq.incrementAndGet());
    }

    @Override
    public RebuildTerminalOutcome awaitRebuildCompletion(TenantHandle tenant, String indexName,
                                                           RebuildOutcome triggered, Duration timeout) {
        return RebuildTerminalOutcome.READY;
    }
}
