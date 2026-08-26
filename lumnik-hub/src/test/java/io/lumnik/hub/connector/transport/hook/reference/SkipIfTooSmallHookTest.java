package io.lumnik.hub.connector.transport.hook.reference;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.hook.FileSourceHookRunner;
import io.lumnik.hub.connector.transport.hook.HookSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SkipIfTooSmallHookTest {

    @Inject
    FileSourceHookRunner runner;

    @Test
    void rejects_files_below_threshold() {
        var hook = new HookSpec("kotlin", "reference/skip-if-too-small.kt");
        var tiny = new FileDescriptor("/tiny.csv", 10, Instant.now(), null);
        assertFalse(runner.shouldProcess(hook, tiny, null));
    }

    @Test
    void accepts_files_above_threshold() {
        var hook = new HookSpec("kotlin", "reference/skip-if-too-small.kt");
        var ok = new FileDescriptor("/ok.csv", 1000, Instant.now(), null);
        assertTrue(runner.shouldProcess(hook, ok, null));
    }
}
