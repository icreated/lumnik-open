package io.lumnik.common.hook;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * No-op fallback implementation of HookSpi.
 * Active whenever no real HookSpi bean (e.g. HookExecutor from lumnik-hub) is present.
 * Domain modules that run without the hub (standalone tests) use this silently.
 */
@ApplicationScoped
public class NoOpHookSpi implements HookSpi {

    @Override
    public void fire(LifecycleEventSpi event, Object entity) {
        // no-op: no platform present, hooks are not registered
    }

    @Override
    public void fireAction(String action, LifecycleEventSpi.Phase phase, Object payload) {
        // no-op
    }
}
