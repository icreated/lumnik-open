package io.lumnik.hub.manifest;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayDeque;

/**
 * active = carried ∧ allowed ∧ every module in {@code depends} is active. An unknown or inactive
 * dependency makes a module inactive with a reason; it never fails the startup. When several
 * reasons apply, the first of: {@code disabled} (or {@code disabled:<property>} when the manifest names the switch), {@code dependency_cycle}, then the first failing
 * entry of {@code depends} in declaration order.
 */
final class ModuleStatuses {

    private ModuleStatuses() {}

    static List<ModuleStatus> compute(List<ModuleManifest> manifests, Predicate<ModuleManifest> allowed) {
        Map<String, ModuleManifest> byId = new HashMap<>();
        manifests.forEach(m -> byId.put(m.id(), m));
        Map<String, String> reasons = new HashMap<>(); // id -> reason, "" = active
        for (ModuleManifest m : manifests) reason(m, byId, allowed, reasons);
        return manifests.stream()
            .map(m -> {
                String r = reasons.get(m.id());
                return new ModuleStatus(m, r.isEmpty() ? null : r);
            })
            .toList();
    }

    private static String reason(ModuleManifest m, Map<String, ModuleManifest> byId,
                                 Predicate<ModuleManifest> allowed, Map<String, String> memo) {
        String known = memo.get(m.id());
        if (known != null) return known;
        String r = "";
        if (!allowed.test(m)) r = m.enabledBy() == null ? "disabled" : "disabled:" + m.enabledBy();
        else if (reachesItself(m, byId)) r = "dependency_cycle";
        else {
            for (String dep : deps(m)) {
                ModuleManifest d = byId.get(dep);
                if (d == null) { r = "missing_dependency:" + dep; break; }
                if (!reason(d, byId, allowed, memo).isEmpty()) { r = "inactive_dependency:" + dep; break; }
            }
        }
        memo.put(m.id(), r);
        return r;
    }

    private static boolean reachesItself(ModuleManifest m, Map<String, ModuleManifest> byId) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        addReachable(todo, m);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (id.equals(m.id())) return true;
            ModuleManifest d = byId.get(id);
            if (d != null && seen.add(id)) addReachable(todo, d);
        }
        return false;
    }

    /** A null entry names nothing, so it reaches nothing; {@link #reason} still reports it as missing. */
    private static void addReachable(ArrayDeque<String> todo, ModuleManifest m) {
        deps(m).stream().filter(Objects::nonNull).forEach(todo::add);
    }

    /** Exactly as declared: a null dependency is a missing one, never an absent one. */
    private static List<String> deps(ModuleManifest m) {
        return m.depends() == null ? List.of() : m.depends();
    }
}
