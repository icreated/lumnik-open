package io.lumnik.hub.hook;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class HookRegistry {

    /** key = "ClassName|EVENT" → bindings sorted by priority asc */
    private final Map<String, List<HookBinding>> lifecycleIndex = new ConcurrentHashMap<>();
    /** key = "module.action|BEFORE" → bindings */
    private final Map<String, List<HookBinding>> actionIndex = new ConcurrentHashMap<>();
    /** key = "EntityClass|field" → callout binding */
    private final Map<String, CalloutBinding> calloutIndex = new ConcurrentHashMap<>();
    /** flat list for /api/platform/hooks endpoint */
    private final List<HookBinding> allBindings = new ArrayList<>();
    private final List<CalloutBinding> allCallouts = new ArrayList<>();

    public synchronized void register(HookBinding b) {
        if (!b.enabled()) { allBindings.add(b); return; }
        if (b.isLifecycle()) {
            for (LifecycleEvent ev : b.events()) {
                String key = b.targetClassName() + "|" + ev.name();
                lifecycleIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
                lifecycleIndex.get(key).sort(Comparator.comparingInt(HookBinding::priority));
            }
        } else {
            String key = b.action() + "|" + b.actionPhase().name();
            actionIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            actionIndex.get(key).sort(Comparator.comparingInt(HookBinding::priority));
        }
        allBindings.add(b);
    }

    public synchronized void registerCallout(CalloutBinding c) {
        calloutIndex.put(c.entityClassName() + "|" + c.field(), c);
        allCallouts.add(c);
    }

    public List<HookBinding> findFor(Class<?> target, LifecycleEvent event, Long tenantId) {
        List<HookBinding> list = lifecycleIndex.getOrDefault(target.getName() + "|" + event.name(), List.of());
        return list.stream().filter(b -> b.matchesTenant(tenantId)).toList();
    }

    public List<HookBinding> findForAction(String action, LifecycleEvent.Phase phase, Long tenantId) {
        List<HookBinding> list = actionIndex.getOrDefault(action + "|" + phase.name(), List.of());
        return list.stream().filter(b -> b.matchesTenant(tenantId)).toList();
    }

    public Optional<CalloutBinding> findCallout(String entityClass, String field) {
        return Optional.ofNullable(calloutIndex.get(entityClass + "|" + field));
    }

    public List<HookBinding> allBindings() { return List.copyOf(allBindings); }
    public List<CalloutBinding> allCallouts() { return List.copyOf(allCallouts); }
}
