package io.lumnik.hub.hook.testkit;

import io.lumnik.hub.hook.HookExecutor;
import io.lumnik.hub.hook.HookRegistry;
import io.lumnik.hub.hook.LifecycleEvent;
import jakarta.enterprise.inject.spi.CDI;

/** CDI-backed helpers for tests interacting with the hooks system. */
public final class HookTestKit {
    private HookTestKit() {}

    public static HookRegistry registry() {
        return CDI.current().select(HookRegistry.class).get();
    }

    public static HookExecutor executor() {
        return CDI.current().select(HookExecutor.class).get();
    }

    /** Fire a lifecycle event directly (bypasses HTTP, for unit tests). */
    public static void fire(LifecycleEvent event, Object entity) {
        executor().fire(event, entity);
    }
}
