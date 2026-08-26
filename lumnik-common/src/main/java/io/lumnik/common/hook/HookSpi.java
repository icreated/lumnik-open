package io.lumnik.common.hook;

public interface HookSpi {
    void fire(LifecycleEventSpi event, Object entity);
    void fireAction(String action, LifecycleEventSpi.Phase phase, Object payload);
}
