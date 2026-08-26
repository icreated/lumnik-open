package io.lumnik.hub.hook;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HookManifestScannerIT {

    @Inject HookRegistry registry;

    @Test
    void boot_loaded_sample_hook_from_test_resources() {
        assertThat(registry.allBindings())
            .anyMatch(b -> b.id().equals("test-noop")
                        && b.module().equals("platform-test"));
    }
}
