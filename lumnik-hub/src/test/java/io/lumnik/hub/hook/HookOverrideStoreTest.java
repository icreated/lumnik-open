package io.lumnik.hub.hook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class HookOverrideStoreTest {

    @Test
    void enabled_returns_default_when_no_override() {
        Map<String, Boolean> backing = new HashMap<>();
        BiFunction<Long, String, Boolean> loader = (tid, hid) -> backing.get(tid + "|" + hid);
        HookOverrideStore store = new HookOverrideStore(loader, Duration.ofSeconds(60));
        assertThat(store.isEnabled(1L, "any-hook", true)).isTrue();
        assertThat(store.isEnabled(1L, "any-hook", false)).isFalse();
    }

    @Test
    void enabled_returns_override_when_present() {
        Map<String, Boolean> backing = new HashMap<>();
        backing.put("1|disabled-hook", false);
        BiFunction<Long, String, Boolean> loader = (tid, hid) -> backing.get(tid + "|" + hid);
        HookOverrideStore store = new HookOverrideStore(loader, Duration.ofSeconds(60));
        assertThat(store.isEnabled(1L, "disabled-hook", true)).isFalse();
    }

    @Test
    void cache_invalidation_after_ttl() throws InterruptedException {
        Map<String, Boolean> backing = new HashMap<>();
        backing.put("1|h", false);
        BiFunction<Long, String, Boolean> loader = (tid, hid) -> backing.get(tid + "|" + hid);
        HookOverrideStore store = new HookOverrideStore(loader, Duration.ofMillis(50));

        assertThat(store.isEnabled(1L, "h", true)).isFalse();
        backing.put("1|h", true);
        // Within TTL — still cached
        assertThat(store.isEnabled(1L, "h", true)).isFalse();
        Thread.sleep(80);
        assertThat(store.isEnabled(1L, "h", true)).isTrue();
    }

    @Test
    void invalidate_tenant_clears_only_that_tenant() {
        Map<String, Boolean> backing = new HashMap<>();
        backing.put("1|h", false);
        backing.put("2|h", false);
        BiFunction<Long, String, Boolean> loader = (tid, hid) -> backing.get(tid + "|" + hid);
        HookOverrideStore store = new HookOverrideStore(loader, Duration.ofSeconds(60));
        store.isEnabled(1L, "h", true);   // warm
        store.isEnabled(2L, "h", true);   // warm

        backing.put("1|h", true);
        backing.put("2|h", true);
        store.invalidate(1L, "h");

        assertThat(store.isEnabled(1L, "h", true)).isTrue();
        assertThat(store.isEnabled(2L, "h", true)).isFalse(); // stale
    }
}
