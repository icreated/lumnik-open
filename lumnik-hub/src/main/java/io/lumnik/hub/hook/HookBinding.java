package io.lumnik.hub.hook;

import java.util.List;
import java.util.Set;

public record HookBinding(
    String id,
    String module,
    String description,
    String targetClassName,         // null for action hooks
    Set<LifecycleEvent> events,     // empty for action hooks
    String action,                  // null for lifecycle hooks; format "module.action"
    LifecycleEvent.Phase actionPhase, // BEFORE/AFTER for action hooks
    HookHandler handler,
    int priority,
    List<Object> tenants,           // "*" or list of Long
    boolean enabled
) {
    public boolean matchesTenant(Long tenantId) {
        if (tenants == null || tenants.isEmpty()) return true;
        if (tenantId == null) return tenants.stream().anyMatch("*"::equals);
        for (Object t : tenants) {
            if ("*".equals(t)) return true;
            if (t instanceof Number n && n.longValue() == tenantId) return true;
        }
        return false;
    }

    public boolean isLifecycle()  { return action == null; }
    public boolean isAction()     { return action != null; }
}
