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
        for (Object t : tenants) {   // one pass: the dispatch path asks this for every binding
            if ("*".equals(t)) return true;
            if (tenantId != null && t instanceof Number n && n.longValue() == tenantId) return true;
        }
        return false;
    }

    /** Whether this binding runs for every tenant: no {@code tenants}, or a {@code "*"} among them. */
    public boolean isGlobal() {
        return tenants == null || tenants.isEmpty() || tenants.stream().anyMatch("*"::equals);
    }

    /**
     * The tenants this binding runs for, as one tenant's admin may read them: {@code ["*"]} when global, else its own
     * id — never another tenant's (#1992).
     */
    public List<Object> tenantsAsSeenBy(Long tenantId) {
        return isGlobal() ? List.of("*") : List.of(tenantId);
    }

    public boolean isLifecycle()  { return action == null; }
    public boolean isAction()     { return action != null; }
}
