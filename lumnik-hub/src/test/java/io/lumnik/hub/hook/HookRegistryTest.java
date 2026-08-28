package io.lumnik.hub.hook;

import io.lumnik.bpartner.partner.Partner;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookRegistryTest {

    @Test
    void register_indexes_by_target_and_event() {
        HookRegistry r = new HookRegistry();
        HookBinding b = new HookBinding("h1","m","",
            "io.lumnik.bpartner.partner.Partner",
            EnumSet.of(LifecycleEvent.BEFORE_CREATE),
            null, null,
            new HookHandler.JavaHandler("Some.Class"),
            100, List.of("*"), true);
        r.register(b);

        assertThat(r.findFor(Partner.class,
                              LifecycleEvent.BEFORE_CREATE, 42L)).contains(b);
    }

    @Test
    void disabled_binding_not_in_index() {
        HookRegistry r = new HookRegistry();
        HookBinding b = new HookBinding("h2","m","",
            "io.lumnik.bpartner.partner.Partner",
            EnumSet.of(LifecycleEvent.AFTER_UPDATE),
            null, null,
            new HookHandler.JavaHandler("X"),
            100, List.of("*"), false);
        r.register(b);

        assertThat(r.findFor(Partner.class,
                              LifecycleEvent.AFTER_UPDATE, 1L)).isEmpty();
        assertThat(r.allBindings()).contains(b);
    }

    @Test
    void tenant_filter_excludes_non_matching() {
        HookRegistry r = new HookRegistry();
        HookBinding b = new HookBinding("h3","m","",
            "X.Y",
            EnumSet.of(LifecycleEvent.BEFORE_CREATE),
            null, null,
            new HookHandler.JavaHandler("X"),
            100, List.of(42L), true);
        r.register(b);

        assertThat(b.matchesTenant(42L)).isTrue();
        assertThat(b.matchesTenant(43L)).isFalse();
    }
}
